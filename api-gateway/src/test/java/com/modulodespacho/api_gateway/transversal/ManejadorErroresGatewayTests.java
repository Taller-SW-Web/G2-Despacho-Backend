package com.modulodespacho.api_gateway.transversal;

import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ManejadorErroresGatewayTests {

    @Test
    void convierteConexionRechazadaEnProblema503SinExponerElDestino() {
        var intercambio = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/cotizaciones"));
        intercambio.getAttributes().put(FiltroCorrelacion.ATRIBUTO, "correlacion-prueba");
        var manejador = new ManejadorErroresGateway(new ProblemasGateway(JsonMapper.builder().build()));

        manejador.handle(intercambio, new ConnectException("host-interno-privado:8081")).block();

        assertThat(intercambio.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(intercambio.getResponse().getBodyAsString().block())
                .contains("DESP_ERROR_SERVICIO_NO_DISPONIBLE")
                .contains("correlacion-prueba")
                .doesNotContain("host-interno-privado");
    }

    @Test
    void convierteTiempoAgotadoEnProblema503() {
        var intercambio = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/zonas"));
        var manejador = new ManejadorErroresGateway(new ProblemasGateway(JsonMapper.builder().build()));

        manejador.handle(intercambio, new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT)).block();

        assertThat(intercambio.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
