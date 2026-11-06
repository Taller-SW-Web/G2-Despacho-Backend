package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.assertj.core.api.Assertions.*;

/** Firma y HTTP/JWKS reales en loopback. No acredita cuentas ni despliegue G7. */
class IntegracionJwtSeguridadTests {
    private HttpServer servidor;
    private RSAKey clave;
    private String origen;
    private JwtDecoder decodificador;
    private final AtomicInteger consultas = new AtomicInteger();

    @BeforeEach
    void preparar() throws Exception {
        clave = new RSAKeyGenerator(2048).keyID("g7-prueba").generate();
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origen = "http://127.0.0.1:" + servidor.getAddress().getPort();
        servidor.createContext("/api/v1/auth/.well-known/openid-configuration", peticion -> {
            consultas.incrementAndGet();
            responder(peticion, "{\"issuer\":\"" + origen + "/api/v1/auth\",\"jwks_uri\":\""
                    + origen + "/api/v1/auth/.well-known/jwks.json\"}");
        });
        servidor.createContext("/api/v1/auth/.well-known/jwks.json", peticion ->
                responder(peticion, new JWKSet(clave.toPublicJWK()).toString()));
        servidor.start();
        decodificador = new ConfiguracionSeguridad().decodificadorJwt(origen, "api-despacho", "RS256");
    }

    @AfterEach
    void cerrar() { if (servidor != null) servidor.stop(0); }

    @Test
    void validaFirmaYPerfilG7ConRolesVaciosYDescubrimientoReutilizado() throws Exception {
        String token = firmar(Map.of(), null, clave);
        assertThat(decodificador.decode(token).getClaimAsStringList("roles")).isEmpty();
        assertThat(decodificador.decode(token).getSubject()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(consultas.get()).isEqualTo(1);
    }

    @Test
    void rechazaIatAusenteAntesDeQueSpringLoComplete() throws Exception {
        String token = firmar(Map.of(), "iat", clave);
        assertThatThrownBy(() -> decodificador.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rechazaFirmaAjenaAunqueCoincidaKid() throws Exception {
        RSAKey ajena = new RSAKeyGenerator(2048).keyID("g7-prueba").generate();
        String token = firmar(Map.of(), null, ajena);
        assertThatThrownBy(() -> decodificador.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rechazaClaimsAusentesEmisorAjenoYTokenVencido() throws Exception {
        for (String campo : List.of("roles", "jti", "exp")) {
            String token = firmar(Map.of(), campo, clave);
            assertThatThrownBy(() -> decodificador.decode(token)).as(campo).isInstanceOf(JwtException.class);
        }
        String ajeno = firmar(Map.of("iss", "https://emisor-ajeno.example"), null, clave);
        assertThatThrownBy(() -> decodificador.decode(ajeno)).isInstanceOf(JwtException.class);
        String vencido = firmar(Map.of("iat", Date.from(Instant.now().minusSeconds(600)),
                "exp", Date.from(Instant.now().minusSeconds(1))), null, clave);
        assertThatThrownBy(() -> decodificador.decode(vencido)).isInstanceOf(JwtException.class);
    }

    @Test
    void validaServicioParaDespachoYRechazaAudienciaAjenaYRefresco() throws Exception {
        var servicio = Map.<String, Object>of("tipo", "servicio", "sub", "modulo-despacho",
                "aud", List.of("api-despacho"));
        assertThat(decodificador.decode(firmar(servicio, null, clave)).getSubject()).isEqualTo("modulo-despacho");
        var internoG7 = new java.util.HashMap<>(servicio);
        internoG7.put("aud", List.of("api-productos", "api-seguridad", "api-ventas"));
        assertThat(decodificador.decode(firmar(internoG7, null, clave)).getSubject()).isEqualTo("modulo-despacho");
        var ajeno = new java.util.HashMap<>(servicio); ajeno.put("sub", "modulo-ventas"); ajeno.put("aud", List.of("api-ventas"));
        String tokenAjeno = firmar(ajeno, null, clave);
        assertThatThrownBy(() -> decodificador.decode(tokenAjeno)).isInstanceOf(JwtException.class);
        String refresco = firmar(Map.of("tipo", "refresco"), null, clave);
        assertThatThrownBy(() -> decodificador.decode(refresco)).isInstanceOf(JwtException.class);
    }

    private String firmar(Map<String, Object> cambios, String omitir, RSAKey firma) throws Exception {
        var claims = new java.util.HashMap<String, Object>();
        claims.put("iss", origen + "/api/v1/auth");
        claims.put("sub", "11111111-1111-1111-1111-111111111111");
        claims.put("tipo", "acceso"); claims.put("roles", List.of()); claims.put("jti", "prueba-g7");
        claims.put("iat", Date.from(Instant.now())); claims.put("exp", Date.from(Instant.now().plusSeconds(900)));
        claims.putAll(cambios); if (omitir != null) claims.remove(omitir);
        var constructor = new JWTClaimsSet.Builder(); claims.forEach(constructor::claim);
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(firma.getKeyID()).build(), constructor.build());
        token.sign(new RSASSASigner(firma)); return token.serialize();
    }

    private static void responder(com.sun.net.httpserver.HttpExchange peticion, String contenido) throws java.io.IOException {
        byte[] cuerpo = contenido.getBytes(StandardCharsets.UTF_8);
        peticion.getResponseHeaders().set("Content-Type", "application/json");
        peticion.sendResponseHeaders(200, cuerpo.length);
        try (var salida = peticion.getResponseBody()) { salida.write(cuerpo); }
        finally { peticion.close(); }
    }
}
