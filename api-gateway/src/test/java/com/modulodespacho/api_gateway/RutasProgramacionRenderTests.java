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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** El perfil publicado debe distinguir rutas de Gestión y contexto de Operación. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="SEGURIDAD_URL=http://127.0.0.1:1")
@ActiveProfiles("render")
class RutasProgramacionRenderTests {
    static final AtomicReference<String> RECIBIDO = new AtomicReference<>();
    static final String VERSION = "\"" + "a".repeat(64) + "\"";
    static final HttpServer GESTION = servidor("GESTION");
    static final HttpServer OPERACION = servidor("OPERACION");
    @LocalServerPort int puerto;
    @MockitoBean ReactiveJwtDecoder identidad;
    @DynamicPropertySource static void configurar(DynamicPropertyRegistry p) {
        p.add("GESTION_DESPACHOS_URL", () -> "http://127.0.0.1:" + GESTION.getAddress().getPort());
        p.add("OPERACION_REPARTO_URL", () -> "http://127.0.0.1:" + OPERACION.getAddress().getPort());
    }
    @Test void distingueProveedoresYConservaCabecerasCuerpoYCors() {
        when(identidad.decode("gestor-rutas")).thenReturn(Mono.just(Jwt.withTokenValue("gestor-rutas")
                .header("alg","RS256").subject("gestor").claim("tipo","acceso")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build()));
        var cliente = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + puerto).build();
        String origen = "https://despacho-administrativo.vercel.app";
        cliente.options().uri("/api/v1/jornadas/jornada/secuencia").header("Origin", origen)
                .header("Access-Control-Request-Method","PUT")
                .header("Access-Control-Request-Headers","Authorization,Content-Type,If-Match,Idempotency-Key")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin",origen);
        cliente.get().uri("/api/v1/jornadas/jornada/ruta").header("Authorization","Bearer gestor-rutas").header("Origin",origen)
                .exchange().expectStatus().isOk().expectHeader().valueEquals("ETag",VERSION)
                .expectHeader().value("Access-Control-Expose-Headers", v -> assertThat(v).contains("ETag"))
                .expectBody().json("{\"proveedor\":\"GESTION\"}");
        String clave = java.util.UUID.randomUUID().toString(), cuerpo = "{\"despachos\":[]}";
        cliente.put().uri("/api/v1/jornadas/jornada/secuencia").header("Authorization","Bearer gestor-rutas")
                .header("If-Match",VERSION).header("Idempotency-Key",clave).header("Content-Type","application/json")
                .bodyValue(cuerpo).exchange().expectStatus().isOk();
        assertThat(RECIBIDO.get()).contains("GESTION PUT",VERSION,clave,cuerpo,"Bearer gestor-rutas");
        cliente.post().uri("/api/v1/despachos/despacho/reasignacion").header("Authorization","Bearer gestor-rutas")
                .header("If-Match","\"3\"").header("Idempotency-Key",clave).header("Content-Type","application/json")
                .bodyValue("{\"motivo\":\"Cambio\"}").exchange().expectStatus().isOk();
        assertThat(RECIBIDO.get()).contains("GESTION POST","\"3\"",clave,"Cambio");
        cliente.get().uri("/api/v1/jornadas/jornada").header("Authorization","Bearer gestor-rutas")
                .exchange().expectStatus().isOk().expectBody().json("{\"proveedor\":\"OPERACION\"}");
        RECIBIDO.set(null);
        cliente.get().uri("/api/v1/jornadas/jornada/ruta").exchange().expectStatus().isUnauthorized();
        cliente.put().uri("/api/v1/jornadas/jornada/secuencia").exchange().expectStatus().isUnauthorized();
        cliente.post().uri("/internal/v1/capacidad/transferencias").header("Authorization","Bearer gestor-rutas")
                .exchange().expectStatus().isForbidden();
        assertThat(RECIBIDO.get()).isNull();
    }
    private static HttpServer servidor(String nombre) {
        try {
            var s = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            s.createContext("/api/v1", solicitud -> {
                var h = solicitud.getRequestHeaders();
                RECIBIDO.set(nombre + " " + solicitud.getRequestMethod() + " " + solicitud.getRequestURI() + " "
                        + h.getFirst("Authorization") + " " + h.getFirst("If-Match") + " " + h.getFirst("Idempotency-Key")
                        + " " + new String(solicitud.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                byte[] cuerpo = ("{\"proveedor\":\"" + nombre + "\"}").getBytes(StandardCharsets.UTF_8);
                solicitud.getResponseHeaders().set("Content-Type","application/json");
                solicitud.getResponseHeaders().set("ETag",VERSION);
                solicitud.sendResponseHeaders(200,cuerpo.length);
                try (var salida = solicitud.getResponseBody()) { salida.write(cuerpo); }
                solicitud.close();
            });
            s.start(); return s;
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @AfterAll static void cerrar() { GESTION.stop(0); OPERACION.stop(0); }
}
