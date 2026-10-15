package com.modulodespacho.api_gateway.transversal;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class FiltroTamanoSolicitudTests {

    @Test
    void limitaCuerpoFragmentadoAunqueNoExistaContentLength() {
        var fabrica = DefaultDataBufferFactory.sharedInstance;
        var solicitud = MockServerHttpRequest.post("/api/v1/cotizaciones")
                .body(Flux.just(fabrica.wrap(new byte[40]), fabrica.wrap(new byte[40])));
        var intercambio = MockServerWebExchange.from(solicitud);
        var filtro = new FiltroTamanoSolicitud(64, new ProblemasGateway(JsonMapper.builder().build()));

        filtro.filter(intercambio, siguiente -> Mono.error(
                new AssertionError("Una solicitud excesiva no debe llegar al servicio"))).block();

        assertThat(solicitud.getHeaders().getContentLength()).isEqualTo(-1);
        assertThat(intercambio.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }
}
