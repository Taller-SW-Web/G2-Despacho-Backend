package com.modulodespacho.api_gateway.transversal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class FiltroTamanoSolicitudTests {

    @Test
    void limitaCuerpoFragmentadoAunqueNoExistaContentLength() {
        var fabrica = DefaultDataBufferFactory.sharedInstance;
        var solicitud =
                MockServerHttpRequest.post("/api/v1/cotizaciones")
                        .body(Flux.just(fabrica.wrap(new byte[40]), fabrica.wrap(new byte[40])));
        var intercambio = MockServerWebExchange.from(solicitud);
        var filtro =
                new FiltroTamanoSolicitud(
                        64, 128, new ProblemasGateway(JsonMapper.builder().build()));

        filtro.filter(
                        intercambio,
                        siguiente ->
                                Mono.error(
                                        new AssertionError(
                                                "Una solicitud excesiva no debe llegar al servicio")))
                .block();

        assertThat(solicitud.getHeaders().getContentLength()).isEqualTo(-1);
        assertThat(intercambio.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void limiteAmpliadoSoloParaLasDosEscriturasGeograficasExactas() {
        for (String ruta :
                new String[] {
                    "/api/v1/distritos/geometrias/validaciones",
                    "/api/v1/distritos/geometrias/importaciones",
                    "/api/v1/cotizaciones",
                    "/api/v1/distritos/geometrias/validaciones/otra"
                }) {
            var fabrica = DefaultDataBufferFactory.sharedInstance;
            var intercambio =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.post(ruta)
                                    .body(
                                            Flux.just(
                                                    fabrica.wrap(new byte[40]),
                                                    fabrica.wrap(new byte[40]))));
            var filtro =
                    new FiltroTamanoSolicitud(
                            64, 128, new ProblemasGateway(JsonMapper.builder().build()));
            var enviado = new java.util.concurrent.atomic.AtomicBoolean();
            filtro.filter(
                            intercambio,
                            siguiente -> {
                                enviado.set(true);
                                return org.springframework.core.io.buffer.DataBufferUtils.join(
                                                siguiente.getRequest().getBody())
                                        .doOnNext(
                                                org.springframework.core.io.buffer.DataBufferUtils
                                                        ::release)
                                        .then();
                            })
                    .block();
            boolean permitida = ruta.endsWith("validaciones") || ruta.endsWith("importaciones");
            assertThat(enviado.get()).as(ruta).isEqualTo(permitida);
            if (!permitida)
                assertThat(intercambio.getResponse().getStatusCode())
                        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        }
    }

    @Test
    void rechazaGeografiasExcesivasConLongitudYPorFragmentos() {
        var filtro =
                new FiltroTamanoSolicitud(
                        64, 128, new ProblemasGateway(JsonMapper.builder().build()));
        for (boolean declarar : new boolean[] {false, true}) {
            var solicitud = MockServerHttpRequest.post("/api/v1/distritos/geometrias/validaciones");
            if (declarar) solicitud.header("Content-Length", "160");
            var fabrica = DefaultDataBufferFactory.sharedInstance;
            var intercambio =
                    MockServerWebExchange.from(
                            solicitud.body(
                                    Flux.just(
                                            fabrica.wrap(new byte[80]),
                                            fabrica.wrap(new byte[80]))));
            filtro.filter(
                            intercambio,
                            siguiente -> Mono.error(new AssertionError("No debe enviarse")))
                    .block();
            assertThat(intercambio.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        }
    }
}
