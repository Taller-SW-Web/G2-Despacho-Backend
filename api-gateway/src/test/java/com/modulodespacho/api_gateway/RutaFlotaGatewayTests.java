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
import org.springframework.http.HttpMethod;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "SEGURIDAD_URL=http://127.0.0.1:1")
@ActiveProfiles("render")
class RutaFlotaGatewayTests {
    static final AtomicReference<String> RECIBIDO = new AtomicReference<>();
    static final HttpServer OPERACION = crearDestino();
    @LocalServerPort int puerto;
    @MockitoBean ReactiveJwtDecoder decodificador;
    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry propiedades) {
        propiedades.add("OPERACION_REPARTO_URL", () -> "http://127.0.0.1:" + OPERACION.getAddress().getPort());
    }
    @Test
    void enrutaCatalogosConCuerpoYJwtYPermiteSoloLosDosOrigenesPublicados() {
        when(decodificador.decode("gestor")).thenReturn(Mono.just(Jwt.withTokenValue("gestor")
                .header("alg","RS256").subject("gestor").claim("tipo","acceso")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build()));
        WebTestClient cliente = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + puerto).build();
        for (String origen : java.util.List.of("https://despacho-administrativo.vercel.app","https://despacho-repartidor.vercel.app")) {
            cliente.options().uri("/api/v1/repartidores").header("Origin",origen)
                    .header("Access-Control-Request-Method","PUT").header("Access-Control-Request-Headers","Authorization,Content-Type")
                    .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin",origen);
        }
        cliente.options().uri("/api/v1/repartidores").header("Origin","https://otro.example")
                .header("Access-Control-Request-Method","GET").exchange().expectStatus().isForbidden();
        for (HttpMethod metodo : java.util.List.of(HttpMethod.GET,HttpMethod.POST,HttpMethod.PUT,HttpMethod.PATCH)) {
            String ruta = metodo == HttpMethod.GET ? "/api/v1/repartidores?pagina=2" : "/api/v1/furgonetas/identificador/estado";
            cliente.method(metodo).uri(ruta).header("Authorization","Bearer gestor")
                    .header("Content-Type","application/json").bodyValue("{\"estado\":\"DISPONIBLE\"}")
                    .exchange().expectStatus().isOk();
            assertThat(RECIBIDO.get()).contains(metodo.name(), "Bearer gestor", "{\"estado\":\"DISPONIBLE\"}");
        }
        RECIBIDO.set(null);
        cliente.get().uri("/api/v1/repartidores").exchange().expectStatus().isUnauthorized();
        assertThat(RECIBIDO.get()).isNull();
    }
    static HttpServer crearDestino() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            servidor.createContext("/api/v1", intercambio -> {
                RECIBIDO.set(intercambio.getRequestMethod() + " " + intercambio.getRequestURI() + " "
                        + intercambio.getRequestHeaders().getFirst("Authorization") + " "
                        + new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] respuesta = "{}".getBytes(StandardCharsets.UTF_8);
                intercambio.getResponseHeaders().set("Content-Type","application/json");
                intercambio.sendResponseHeaders(200,respuesta.length);
                try (var salida = intercambio.getResponseBody()) { salida.write(respuesta); }
                intercambio.close();
            });
            servidor.start(); return servidor;
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @AfterAll static void cerrar() { OPERACION.stop(0); }
}
