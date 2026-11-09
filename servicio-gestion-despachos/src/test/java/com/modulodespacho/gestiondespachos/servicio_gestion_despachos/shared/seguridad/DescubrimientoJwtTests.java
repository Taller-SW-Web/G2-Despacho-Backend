package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;

class DescubrimientoJwtTests {

    @Test
    void distingueLaFallaDelDescubrimientoDeLaDescargaDeClaves() throws Exception {
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origen = "http://127.0.0.1:" + servidor.getAddress().getPort();
        var openIdDisponible = new AtomicBoolean(false);
        servidor.createContext(
                "/api/v1/auth/.well-known/openid-configuration",
                solicitud -> {
                    if (openIdDisponible.get()) {
                        responder(
                                solicitud,
                                200,
                                "{\"issuer\":\""
                                        + origen
                                        + "\",\"jwks_uri\":\""
                                        + origen
                                        + "/claves\"}");
                    } else {
                        responder(solicitud, 503, "{}");
                    }
                });
        servidor.createContext("/claves", solicitud -> responder(solicitud, 502, "{}"));
        servidor.start();
        try {
            var token =
                    new SignedJWT(
                            new JWSHeader(JWSAlgorithm.RS256),
                            new JWTClaimsSet.Builder()
                                    .issuer(origen)
                                    .subject("modulo-chatbot")
                                    .build());
            token.sign(new RSASSASigner(new RSAKeyGenerator(2048).generate()));
            var decodificador =
                    new ConfiguracionSeguridad().decodificadorJwt(origen, "api-despacho", "RS256");
            BadJwtException descubrimiento =
                    assertThrows(
                            BadJwtException.class, () -> decodificador.decode(token.serialize()));
            assertThat(DiagnosticoJwt.describir(descubrimiento, true))
                    .contains("etapa=openid motivo=respuesta_http estado_g7=503");
            openIdDisponible.set(true);
            BadJwtException claves =
                    assertThrows(
                            BadJwtException.class, () -> decodificador.decode(token.serialize()));
            assertThat(DiagnosticoJwt.describir(claves, true))
                    .contains("etapa=jwks motivo=respuesta_http estado_g7=502");
        } finally {
            servidor.stop(0);
        }
    }

    private static void responder(HttpExchange solicitud, int estado, String cuerpo)
            throws IOException {
        try (solicitud) {
            byte[] contenido = cuerpo.getBytes(StandardCharsets.UTF_8);
            solicitud.getResponseHeaders().set("Content-Type", "application/json");
            solicitud.sendResponseHeaders(estado, contenido.length);
            solicitud.getResponseBody().write(contenido);
        }
    }
}
