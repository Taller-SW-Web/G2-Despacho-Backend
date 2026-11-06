package com.modulodespacho.api_gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** El proxy publicado expone únicamente el acceso humano; /me conserva autenticación JWT. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("render")
class RutasSesionRenderTests {
    private static final String[] ORIGENES = {"https://despacho-administrativo.vercel.app", "https://despacho-repartidor.vercel.app"};
    private static final AtomicReference<String> RECIBIDO = new AtomicReference<>();
    private static final HttpServer SEGURIDAD = servidor();
    @LocalServerPort int puerto;
    @MockitoBean ReactiveJwtDecoder identidad;
    @DynamicPropertySource static void configurar(DynamicPropertyRegistry propiedades) {
        propiedades.add("SEGURIDAD_URL", () -> "http://127.0.0.1:" + SEGURIDAD.getAddress().getPort());
    }
    @Test void publicaAccesoSinTokenYProtegePerfil() {
        var cliente = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + puerto).build();
        for (String ruta : new String[]{"login", "otp/solicitar", "otp/verificar", "refresh", "logout"}) {
            cliente.post().uri("/api/v1/auth/" + ruta).header("Content-Type", "application/json")
                    .bodyValue("{\"prueba\":\"valor\"}").exchange().expectStatus().isOk();
            assertThat(RECIBIDO.get()).contains("POST /api/v1/auth/" + ruta, "valor");
        }
        RECIBIDO.set(null);
        cliente.get().uri("/api/v1/auth/me").exchange().expectStatus().isUnauthorized();
        assertThat(RECIBIDO.get()).isNull();
        when(identidad.decode("acceso")).thenReturn(Mono.just(Jwt.withTokenValue("acceso").header("alg", "RS256")
                .subject("persona").claim("tipo", "acceso").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build()));
        cliente.get().uri("/api/v1/auth/me").header("Authorization", "Bearer acceso").exchange().expectStatus().isOk();
        assertThat(RECIBIDO.get()).contains("GET /api/v1/auth/me", "Bearer acceso");
        RECIBIDO.set(null);
        cliente.post().uri("/api/v1/auth/token").exchange().expectStatus().isUnauthorized();
        assertThat(RECIBIDO.get()).isNull();
    }
    @Test void conservaUnSoloOrigenCorsCuandoSeguridadTambienLoPublica() {
        var cliente = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + puerto).build();
        for (String origen : ORIGENES) {
            for (String ruta : new String[]{"login", "otp/solicitar", "otp/verificar", "refresh", "logout"}) {
                cliente.post().uri("/api/v1/auth/" + ruta).header("Origin", origen)
                        .header("Content-Type", "application/json").bodyValue("{\"prueba\":\"valor\"}")
                        .exchange().expectStatus().isOk()
                        .expectHeader().valueEquals("Access-Control-Allow-Origin", origen)
                        .expectBody().json("{}");
                assertThat(RECIBIDO.get()).contains("POST /api/v1/auth/" + ruta, "valor");
            }
        }
    }
    @Test void conservaCorsYEstadoOriginalEnRespuestasDeSeguridad() {
        var cliente = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + puerto).build();
        for (String origen : ORIGENES) {
            for (int estado : new int[]{202, 204, 401, 429, 503}) {
                var respuesta = cliente.post().uri("/api/v1/auth/login?estadoPrueba=" + estado)
                        .header("Origin", origen).header("Content-Type", "application/json").bodyValue("{}")
                        .exchange().expectStatus().isEqualTo(estado)
                        .expectHeader().valueEquals("Access-Control-Allow-Origin", origen);
                if (estado == 204) respuesta.expectBody().isEmpty();
                else respuesta.expectBody().json("{}");
            }
        }
    }
    @Test void permitePreflightPublicadoYRechazaOrigenAjenoSinLlamarASeguridad() {
        var cliente = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + puerto).build();
        for (String origen : ORIGENES) {
            RECIBIDO.set(null);
            cliente.options().uri("/api/v1/auth/login").header("Origin", origen)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "Content-Type")
                    .exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("Access-Control-Allow-Origin", origen);
            assertThat(RECIBIDO.get()).isNull();
        }
        for (String metodo : new String[]{"OPTIONS", "POST"}) {
            RECIBIDO.set(null);
            cliente.method(org.springframework.http.HttpMethod.valueOf(metodo)).uri("/api/v1/auth/login")
                    .header("Origin", "https://ajeno.example").header("Access-Control-Request-Method", "POST")
                    .exchange().expectStatus().isForbidden()
                    .expectHeader().doesNotExist("Access-Control-Allow-Origin");
            assertThat(RECIBIDO.get()).isNull();
        }
    }
    private static HttpServer servidor() {
        try {
            var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/api/v1/auth", solicitud -> {
                RECIBIDO.set(solicitud.getRequestMethod() + " " + solicitud.getRequestURI() + " "
                        + solicitud.getRequestHeaders().getFirst("Authorization") + " "
                        + new String(solicitud.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] respuesta = "{}".getBytes(StandardCharsets.UTF_8);
                solicitud.getResponseHeaders().set("Content-Type", "application/json");
                // Reproduce G7: el destino también añade CORS y el Gateway ya lo añadió.
                String origen = solicitud.getRequestHeaders().getFirst("Origin");
                if (origen != null) solicitud.getResponseHeaders().set("Access-Control-Allow-Origin", origen);
                String consulta = solicitud.getRequestURI().getQuery();
                int estado = consulta == null ? 200 : Integer.parseInt(consulta.substring("estadoPrueba=".length()));
                solicitud.sendResponseHeaders(estado, estado == 204 ? -1 : respuesta.length);
                if (estado != 204) try (var salida = solicitud.getResponseBody()) { salida.write(respuesta); }
                solicitud.close();
            });
            servidor.start(); return servidor;
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @AfterAll static void cerrar() { SEGURIDAD.stop(0); }
}
