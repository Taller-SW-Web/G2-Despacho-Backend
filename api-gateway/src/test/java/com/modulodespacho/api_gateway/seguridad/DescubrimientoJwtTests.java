package com.modulodespacho.api_gateway.seguridad;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

class DescubrimientoJwtTests {

    private static final String EMISOR = "https://seguridad-prueba.example/api/v1/auth";
    private static RSAKey clavePublicada;
    private static DisposableServer servidor;
    private static ReactiveJwtDecoder decodificador;

    @BeforeAll
    static void prepararSeguridadSimulada() throws JOSEException {
        clavePublicada = new RSAKeyGenerator(2048).keyID("clave-prueba").generate();
        servidor = HttpServer.create().host("127.0.0.1").port(0)
                .route(rutas -> rutas
                        .get("/api/v1/auth/.well-known/openid-configuration", (solicitud, respuesta) ->
                                respuesta.header("Content-Type", "application/json").sendString(Mono.fromSupplier(() ->
                                        "{\"issuer\":\"" + EMISOR + "\",\"jwks_uri\":\"http://127.0.0.1:"
                                                + servidor.port() + "/claves\"}")))
                        .get("/claves", (solicitud, respuesta) -> respuesta.header("Content-Type", "application/json")
                                .sendString(Mono.fromSupplier(() -> new JWKSet(clavePublicada.toPublicJWK()).toString()))))
                .bindNow();
        decodificador = new ConfiguracionSeguridad().decodificadorJwt(WebClient.builder(),
                "http://127.0.0.1:" + servidor.port(), "api-despacho", "RS256");
    }

    @AfterAll
    static void cerrarServidor() {
        if (servidor != null) {
            servidor.disposeNow();
        }
    }

    private String firmar(RSAKey clave, String tipo, String emisor, List<String> audiencia,
            Instant vencimiento) throws JOSEException {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(emisor)
                .subject("modulo-chatbot").claim("tipo", tipo).claim("scope", "cotizaciones:calcular")
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(vencimiento));
        if (audiencia != null) {
            claims.audience(audiencia);
        }
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID("clave-prueba").build(), claims.build());
        token.sign(new RSASSASigner(clave));
        return token.serialize();
    }

    @Test
    void descubreEmisorYClavesYValidaFirmaDeServicio() throws JOSEException {
        String token = firmar(clavePublicada, "servicio", EMISOR, List.of("api-despacho"),
                Instant.now().plusSeconds(300));
        StepVerifier.create(decodificador.decode(token))
                .expectNextMatches(jwt -> jwt.getSubject().equals("modulo-chatbot"))
                .verifyComplete();
    }

    @Test
    void rechazaFirmaDeOtraClaveAunqueLosClaimsSeanValidos() throws JOSEException {
        RSAKey claveAjena = new RSAKeyGenerator(2048).keyID("clave-prueba").generate();
        String token = firmar(claveAjena, "servicio", EMISOR, List.of("api-despacho"),
                Instant.now().plusSeconds(300));
        StepVerifier.create(decodificador.decode(token)).expectError(BadJwtException.class).verify();
    }

    @Test
    void rechazaEmisorAudienciaYVencimientoIncorrectos() throws JOSEException {
        List<String> tokens = List.of(
                firmar(clavePublicada, "servicio", "https://emisor-ajeno.example",
                        List.of("api-despacho"), Instant.now().plusSeconds(300)),
                firmar(clavePublicada, "servicio", EMISOR, List.of("api-ventas"),
                        Instant.now().plusSeconds(300)),
                firmar(clavePublicada, "servicio", EMISOR, null, Instant.now().plusSeconds(300)),
                firmar(clavePublicada, "servicio", EMISOR, List.of("api-despacho"),
                        Instant.now().minusSeconds(300)),
                firmar(clavePublicada, "refresco", EMISOR, null, Instant.now().plusSeconds(300)));
        for (String token : tokens) {
            StepVerifier.create(decodificador.decode(token)).expectError(BadJwtException.class).verify();
        }
    }

    @Test
    void aceptaAccesoHumanoFirmadoSinAudiencia() throws JOSEException {
        String token = firmar(clavePublicada, "acceso", EMISOR, null, Instant.now().plusSeconds(300));
        StepVerifier.create(decodificador.decode(token))
                .expectNextMatches(jwt -> "acceso".equals(jwt.getClaimAsString("tipo")))
                .verifyComplete();
    }
}
