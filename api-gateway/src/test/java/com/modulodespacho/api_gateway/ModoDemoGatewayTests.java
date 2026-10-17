package com.modulodespacho.api_gateway;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"DEMO_SIN_AUTENTICACION=true", "SEGURIDAD_URL="})
@ActiveProfiles("render")
class ModoDemoGatewayTests {
    static final AtomicReference<String> AUTORIZACION = new AtomicReference<>();
    static final HttpServer DESTINO = crearDestino();
    @LocalServerPort int puerto;
    @Autowired ApplicationContext contexto;

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry propiedades) {
        propiedades.add("GESTION_DESPACHOS_URL", () -> "http://127.0.0.1:" + DESTINO.getAddress().getPort());
        propiedades.add("OPERACION_REPARTO_URL", () -> "http://127.0.0.1:" + DESTINO.getAddress().getPort());
    }

    @Test
    void enrutaAmbosServiciosYDistritosSinJwtNiEmisorDeSeguridad() {
        assertThat(contexto.getBeansOfType(ReactiveJwtDecoder.class)).isEmpty();
        for (String ruta : java.util.List.of("/api/v1/zonas", "/api/v1/distritos", "/api/v1/repartidores")) {
            cliente().get().uri(ruta).exchange().expectStatus().isOk();
            assertThat(AUTORIZACION.get()).isNull();
        }
        cliente().get().uri("/api/v1/zonas").header("Authorization", "Bearer vencido")
                .exchange().expectStatus().isOk();
    }

    @Test
    void conservaCorsYNoPublicaRutasInternasNiOtrosActuadores() {
        cliente().options().uri("/api/v1/zonas")
                .header("Origin", "https://despacho-administrativo.vercel.app")
                .header("Access-Control-Request-Method", "POST")
                .exchange().expectStatus().isOk();
        cliente().options().uri("/api/v1/zonas").header("Origin", "https://otro.example")
                .header("Access-Control-Request-Method", "POST").exchange().expectStatus().isForbidden();
        cliente().get().uri("/actuator/beans").exchange().expectStatus().is4xxClientError();
        cliente().post().uri("/internal/v1/operacion/consulta-incidencias")
                .exchange().expectStatus().is4xxClientError();
    }

    private WebTestClient cliente() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + puerto).build();
    }

    private static HttpServer crearDestino() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/api/v1", intercambio -> {
                AUTORIZACION.set(intercambio.getRequestHeaders().getFirst("Authorization"));
                byte[] datos = "{}".getBytes(StandardCharsets.UTF_8);
                intercambio.getResponseHeaders().set("Content-Type", "application/json");
                intercambio.sendResponseHeaders(200, datos.length);
                try (var salida = intercambio.getResponseBody()) { salida.write(datos); }
                intercambio.close();
            });
            servidor.start();
            return servidor;
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @AfterAll static void cerrar() { DESTINO.stop(0); }
}
