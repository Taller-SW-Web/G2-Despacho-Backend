package com.modulodespacho.api_gateway;

import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"SEGURIDAD_URL=http://localhost:9999", "SOLICITUD_MAXIMO_BYTES=128"})
class ApiGatewayApplicationTests {

    private static final DisposableServer GESTION = destino("gestion");
    private static final DisposableServer OPERACION = destino("operacion");
    private static final String CORRELACION = "35ac19fa-4b25-4a98-bbdd-882234ec1a2c";

    @LocalServerPort
    int puerto;

    @MockitoBean
    ReactiveJwtDecoder decodificadorJwt;

    private WebTestClient cliente;

    private static DisposableServer destino(String nombre) {
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((solicitud, respuesta) -> respuesta
                        .header("X-Destino-Prueba", nombre)
                        .header("X-Token-Prueba", solicitud.requestHeaders().get(HttpHeaders.AUTHORIZATION, ""))
                        .header("X-Correlacion-Prueba", solicitud.requestHeaders().get("X-Correlation-Id", ""))
                        .sendString(solicitud.receive().asString().defaultIfEmpty("")
                                .map(cuerpo -> solicitud.uri() + "|" + cuerpo)))
                .bindNow();
    }

    @DynamicPropertySource
    static void destinos(DynamicPropertyRegistry propiedades) {
        propiedades.add("GESTION_DESPACHOS_URL", () -> "http://127.0.0.1:" + GESTION.port());
        propiedades.add("OPERACION_REPARTO_URL", () -> "http://127.0.0.1:" + OPERACION.port());
    }

    @BeforeEach
    void preparar() {
        cliente = WebTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
        Jwt token = Jwt.withTokenValue("prueba").header("alg", "RS256")
                .subject("modulo-chatbot").claim("tipo", "servicio")
                .claim("scope", "cotizaciones:calcular").audience(java.util.List.of("api-despacho"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(decodificadorJwt.decode(anyString())).thenReturn(Mono.just(token));
        when(decodificadorJwt.decode("invalido")).thenReturn(Mono.error(new BadJwtException("Token inválido")));
    }

    @AfterAll
    static void cerrarDestinos() {
        GESTION.disposeNow();
        OPERACION.disposeNow();
    }

    @Test
    void reenviaRutaCuerpoTokenYCorrelacionAGestion() {
        cliente.post().uri("/api/v1/cotizaciones?prueba=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer prueba")
                .header("X-Correlation-Id", CORRELACION)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"destino\":{\"distrito\":\"San Borja\"}}")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Destino-Prueba", "gestion")
                .expectHeader().valueEquals("X-Token-Prueba", "Bearer prueba")
                .expectHeader().valueEquals("X-Correlacion-Prueba", CORRELACION)
                .expectHeader().valueEquals("X-Correlation-Id", CORRELACION)
                .expectBody(String.class).isEqualTo(
                        "/api/v1/cotizaciones?prueba=1|{\"destino\":{\"distrito\":\"San Borja\"}}");
    }

    @Test
    void enrutaFurgonetasYRepartidorAOperacion() {
        for (String ruta : java.util.List.of("/api/v1/furgonetas", "/api/v1/repartidor/mi-ruta",
                "/api/v1/jornadas/jor-1")) {
            cliente.get().uri(ruta).header(HttpHeaders.AUTHORIZATION, "Bearer prueba")
                    .exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("X-Destino-Prueba", "operacion");
        }
    }

    @Test
    void enrutaConsultaYSecuenciaAdministrativasAGestion() {
        cliente.get().uri("/api/v1/jornadas/jor-1/ruta").header(HttpHeaders.AUTHORIZATION, "Bearer prueba")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Destino-Prueba", "gestion");
        cliente.put().uri("/api/v1/jornadas/jor-1/secuencia").header(HttpHeaders.AUTHORIZATION, "Bearer prueba")
                .header("If-Match", "\"version\"").header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .bodyValue("{\"despachos\":[]}").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Destino-Prueba", "gestion");
    }

    @Test
    void rechazaTokenAusenteEInvalidoConProblemaContractual() {
        cliente.post().uri("/api/v1/cotizaciones").exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().exists("X-Correlation-Id")
                .expectBody().jsonPath("$.code").isEqualTo("DESP_ERROR_TOKEN_INVALIDO");
        cliente.post().uri("/api/v1/cotizaciones")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalido")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void permitePreflightDeUnOrigenConfigurado() {
        cliente.options().uri("/api/v1/cotizaciones")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
    }

    @Test
    void permitePreflightDelGuardadoVersionadoDeRuta() {
        cliente.options().uri("/api/v1/jornadas/jor-1/secuencia")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,if-match,idempotency-key")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
    }

    @Test
    void rechazaOrigenNoAutorizado() {
        cliente.options().uri("/api/v1/cotizaciones")
                .header(HttpHeaders.ORIGIN, "https://origen-no-autorizado.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void noPublicaRutasInternasNiActuatorAdicional() {
        for (String ruta : java.util.List.of("/internal/v1/capacidad/reservas", "/actuator/env")) {
            cliente.get().uri(ruta).header(HttpHeaders.AUTHORIZATION, "Bearer prueba")
                    .exchange().expectStatus().isForbidden();
        }
        cliente.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void rechazaCuerpoQueSuperaElLimite() {
        cliente.post().uri("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer prueba")
                .bodyValue("x".repeat(129)).exchange().expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.code").isEqualTo("DESP_ERROR_SOLICITUD_DEMASIADO_GRANDE");
    }
}
