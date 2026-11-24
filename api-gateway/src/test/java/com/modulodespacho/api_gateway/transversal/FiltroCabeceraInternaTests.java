package com.modulodespacho.api_gateway.transversal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class FiltroCabeceraInternaTests {

    private static final String ENCABEZADO = FiltroCabeceraInterna.ENCABEZADO;

    @Test
    void marcaLasRutasDeNegocioConElSecreto() {
        assertThat(reenviado("secreto", "/api/v1/zonas", null).getFirst(ENCABEZADO))
                .isEqualTo("secreto");
        assertThat(reenviado("secreto", "/api/v1/jornadas/j1/ruta", null).getFirst(ENCABEZADO))
                .isEqualTo("secreto");
    }

    @Test
    void sobrescribeLaCabeceraEnviadaPorElCliente() {
        assertThat(reenviado("secreto", "/api/v1/zonas", "falso").get(ENCABEZADO))
                .containsExactly("secreto");
    }

    @Test
    void noEnviaLaCabeceraASeguridadNiAlActuator() {
        for (String ruta :
                new String[] {
                    "/api/v1/auth/login", "/api/v1/auth", "/actuator/health", "/internal/v1/x"
                }) {
            assertThat(reenviado("secreto", ruta, "falso").containsHeader(ENCABEZADO))
                    .as(ruta)
                    .isFalse();
        }
    }

    @Test
    void sinSecretoSoloEliminaLaCabeceraDelCliente() {
        assertThat(reenviado("", "/api/v1/zonas", "falso").containsHeader(ENCABEZADO)).isFalse();
        assertThat(reenviado("  ", "/api/v1/zonas", null).containsHeader(ENCABEZADO)).isFalse();
        assertThat(reenviado(null, "/api/v1/zonas", null).containsHeader(ENCABEZADO)).isFalse();
    }

    private static HttpHeaders reenviado(String secreto, String ruta, String delCliente) {
        var solicitud = MockServerHttpRequest.get(ruta);
        if (delCliente != null) {
            solicitud.header(ENCABEZADO, delCliente);
        }
        var recibidas = new AtomicReference<HttpHeaders>();
        new FiltroCabeceraInterna(secreto)
                .filter(
                        MockServerWebExchange.from(solicitud),
                        intercambio -> {
                            recibidas.set(intercambio.getRequest().getHeaders());
                            return Mono.empty();
                        })
                .block();
        return recibidas.get();
    }
}
