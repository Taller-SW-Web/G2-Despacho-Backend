package com.modulodespacho.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Comprueba el arranque sin sustituir el decodificador JWT por un mock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "SEGURIDAD_URL=http://127.0.0.1:1")
class ArranqueSeguridadGatewayTests {

    @LocalServerPort
    int puerto;

    @Autowired
    ReactiveJwtDecoder decodificadorJwt;

    @Test
    void iniciaConDecodificadorRealYRespondeSaludSinConsultarElEmisor() {
        assertNotNull(decodificadorJwt);
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + puerto).build()
                .get().uri("/actuator/health")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
