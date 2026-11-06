package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import static org.assertj.core.api.Assertions.assertThat;

class ValidadorTokenDespachoTests {

    private final ValidadorTokenDespacho validador = new ValidadorTokenDespacho("api-despacho");

    private Jwt token(String tipo, List<String> audiencia) {
        return token(tipo, audiencia, "acceso".equals(tipo) ? "11111111-1111-1111-1111-111111111111" : "modulo-despacho");
    }

    private Jwt token(String tipo, List<String> audiencia, String sujeto) {
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject(sujeto)
                .claim("tipo", tipo).audience(audiencia)
                .claim("roles", List.of()).claim("scope", "").claim("jti", "id-prueba")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    @Test
    void aceptaServicioParaDespachoYRechazaAudienciaAusenteOAjena() {
        assertThat(validador.validate(token("servicio", List.of("api-despacho"))).hasErrors()).isFalse();
        assertThat(validador.validate(token("servicio", List.of(), "modulo-ventas")).hasErrors()).isTrue();
        assertThat(validador.validate(token("servicio", List.of("api-ventas"), "modulo-ventas")).hasErrors()).isTrue();
    }

    @Test
    void aceptaClienteInternoConAudienciasEmitidasPorG7SinApiDespacho() {
        var audienciasG7 = List.of("api-productos", "api-seguridad", "api-ventas");
        assertThat(validador.validate(token("servicio", audienciasG7)).hasErrors()).isFalse();
        assertThat(validador.validate(token("servicio", List.of())).hasErrors()).isFalse();
        assertThat(validador.validate(token("servicio", audienciasG7, "modulo-marketplace")).hasErrors()).isTrue();
        Jwt sinAudiencia = Jwt.withTokenValue("prueba").header("alg", "RS256").subject("modulo-chatbot")
                .claim("tipo", "servicio").expiresAt(Instant.now().plusSeconds(300)).build();
        assertThat(validador.validate(sinAudiencia).hasErrors()).isTrue();
    }

    @Test
    void aceptaAccesoHumanoSinAudienciaYRechazaRefresco() {
        assertThat(validador.validate(token("acceso", List.of())).hasErrors()).isFalse();
        assertThat(validador.validate(token("refresco", List.of("api-despacho"))).hasErrors()).isTrue();
    }

    @Test
    void rechazaTokenSinVencimiento() {
        Jwt sinVencimiento = Jwt.withTokenValue("prueba").header("alg", "RS256").subject("prueba")
                .claim("tipo", "acceso").build();
        assertThat(validador.validate(sinVencimiento).hasErrors()).isTrue();
    }

    @Test
    void exigeElPerfilDeClaimsDeG7SinExigirRolGlobalDeRepartidor() {
        var base = token("acceso", List.of());
        assertThat(validador.validate(base).hasErrors()).isFalse();
        for (String campo : List.of("iat", "jti", "roles")) {
            Jwt incompleto = Jwt.withTokenValue("prueba").headers(h -> h.putAll(base.getHeaders()))
                    .claims(c -> { c.putAll(base.getClaims()); c.remove(campo); }).build();
            assertThat(validador.validate(incompleto).hasErrors()).as(campo).isTrue();
        }
        for (Object roles : List.of("GESTOR_DESPACHO", List.of(1))) {
            Jwt invalido = Jwt.withTokenValue("prueba").headers(h -> h.putAll(base.getHeaders()))
                    .claims(c -> { c.putAll(base.getClaims()); c.put("roles", roles); }).build();
            assertThat(validador.validate(invalido).hasErrors()).isTrue();
        }
        Jwt sujetoInvalido = Jwt.withTokenValue("prueba").headers(h -> h.putAll(base.getHeaders()))
                .claims(c -> c.putAll(base.getClaims())).subject("no-es-uuid").build();
        assertThat(validador.validate(sujetoInvalido).hasErrors()).isTrue();
    }

    @Test
    void elServicioNoDependeDelClaimScope() {
        var base = token("servicio", List.of("api-despacho"));
        Jwt sinScope = Jwt.withTokenValue("prueba").headers(h -> h.putAll(base.getHeaders()))
                .claims(c -> { c.putAll(base.getClaims()); c.remove("scope"); }).build();
        assertThat(validador.validate(sinScope).hasErrors()).isFalse();
        Jwt scopeVacio = Jwt.withTokenValue("prueba").headers(h -> h.putAll(base.getHeaders()))
                .claims(c -> { c.putAll(base.getClaims()); c.put("scope", ""); }).build();
        assertThat(validador.validate(scopeVacio).hasErrors()).isFalse();
    }

    @Test
    void validacionCompuestaRechazaEmisorAjenoYTokenVencido() {
        var validacionCompleta = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("https://seguridad.example/api/v1/auth"), validador);
        Jwt emisorAjeno = Jwt.withTokenValue("prueba").header("alg", "RS256").subject("prueba")
                .claim("tipo", "acceso").issuer("https://otro-emisor.example")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        Jwt vencido = Jwt.withTokenValue("prueba").header("alg", "RS256").subject("prueba")
                .claim("tipo", "acceso").issuer("https://seguridad.example/api/v1/auth")
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(300)).build();
        assertThat(validacionCompleta.validate(emisorAjeno).hasErrors()).isTrue();
        assertThat(validacionCompleta.validate(vencido).hasErrors()).isTrue();
    }
}
