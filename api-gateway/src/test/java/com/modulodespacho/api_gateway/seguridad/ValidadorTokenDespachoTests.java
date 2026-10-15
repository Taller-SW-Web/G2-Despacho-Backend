package com.modulodespacho.api_gateway.seguridad;

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
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject("identidad-prueba")
                .claim("tipo", tipo).audience(audiencia)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    @Test
    void aceptaServicioParaDespachoYRechazaAudienciaAusenteOAjena() {
        assertThat(validador.validate(token("servicio", List.of("api-despacho"))).hasErrors()).isFalse();
        assertThat(validador.validate(token("servicio", List.of())).hasErrors()).isTrue();
        assertThat(validador.validate(token("servicio", List.of("api-ventas"))).hasErrors()).isTrue();
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
