package com.modulodespacho.api_gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Gateway real y destino HTTP simulado: verifica exclusivamente enrutamiento y propagación. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "SEGURIDAD_URL=http://127.0.0.1:1")
class RutaFallidosGatewayTests {

    private static final AtomicReference<String> CONSULTA = new AtomicReference<>();
    private static final AtomicReference<String> AUTORIZACION = new AtomicReference<>();
    private static final AtomicReference<String> RUTA = new AtomicReference<>();
    private static final HttpServer GESTION = crearDestino();
    @LocalServerPort int puerto;
    @MockitoBean ReactiveJwtDecoder decodificador;

    @DynamicPropertySource
    static void configurarDestino(DynamicPropertyRegistry propiedades) {
        propiedades.add("GESTION_DESPACHOS_URL", () -> "http://127.0.0.1:" + GESTION.getAddress().getPort());
    }

    @Test
    void enrutaFallidosAGestionConConsultaYBearerYRechazaAccesoAnonimo() {
        Jwt token = Jwt.withTokenValue("gestor").header("alg", "RS256").subject("gestor-prueba")
                .claim("tipo", "acceso").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(decodificador.decode("gestor")).thenReturn(Mono.just(token));
        WebTestClient cliente = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + puerto).build();
        cliente.get().uri("/api/v1/despachos/fallidos?pagina=2&tamanoPagina=3")
                .header("Authorization", "Bearer gestor").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.pagina").isEqualTo(2);
        assertThat(CONSULTA.get()).isEqualTo("pagina=2&tamanoPagina=3");
        assertThat(AUTORIZACION.get()).isEqualTo("Bearer gestor");
        cliente.get().uri("/api/v1/catalogos/motivos-fallo").header("Authorization", "Bearer gestor")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.motivos").isArray();
        assertThat(RUTA.get()).isEqualTo("/api/v1/catalogos/motivos-fallo");
        assertThat(AUTORIZACION.get()).isEqualTo("Bearer gestor");
        CONSULTA.set(null);
        cliente.get().uri("/api/v1/despachos/fallidos").exchange().expectStatus().isUnauthorized();
        assertThat(CONSULTA.get()).isNull();
        cliente.post().uri("/internal/v1/operacion/consulta-incidencias").header("Authorization", "Bearer gestor")
                .exchange().expectStatus().isForbidden();
    }

    private static HttpServer crearDestino() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/api/v1/despachos/fallidos", intercambio -> {
                CONSULTA.set(intercambio.getRequestURI().getRawQuery());
                AUTORIZACION.set(intercambio.getRequestHeaders().getFirst("Authorization"));
                byte[] respuesta = "{\"pagina\":2}".getBytes(StandardCharsets.UTF_8);
                intercambio.getResponseHeaders().set("Content-Type", "application/json");
                intercambio.sendResponseHeaders(200, respuesta.length);
                try (var salida = intercambio.getResponseBody()) { salida.write(respuesta); }
                intercambio.close();
            });
            servidor.createContext("/api/v1/catalogos/motivos-fallo", intercambio -> {
                RUTA.set(intercambio.getRequestURI().getPath());
                AUTORIZACION.set(intercambio.getRequestHeaders().getFirst("Authorization"));
                byte[] respuesta = "{\"motivos\":[]}".getBytes(StandardCharsets.UTF_8);
                intercambio.getResponseHeaders().set("Content-Type", "application/json");
                intercambio.sendResponseHeaders(200, respuesta.length);
                try (var salida = intercambio.getResponseBody()) { salida.write(respuesta); }
                intercambio.close();
            });
            servidor.start();
            return servidor;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @AfterAll
    static void cerrarDestino() { GESTION.stop(0); }
}
