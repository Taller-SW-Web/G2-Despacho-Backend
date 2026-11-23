package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class FiltroAccesoInternoTests {

    // Igual que el JsonMapper de Spring: las propiedades de ProblemDetail van en la raíz.
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                    .build();
    private static final String SECRETO = "secreto-interno";

    @Test
    void sinCabeceraRespondeNoEncontradoSinPasarAlServicio() throws Exception {
        var solicitud = solicitud("GET", "/api/v1/zonas", null);
        solicitud.setAttribute(FiltroCorrelacion.ATRIBUTO, "correlacion-1");
        var cadena = new MockFilterChain();

        var respuesta = filtrar(filtro(SECRETO, true), solicitud, cadena);

        assertThat(respuesta.getStatus()).isEqualTo(404);
        assertThat(cadena.getRequest()).isNull();
        assertThat(respuesta.getContentType()).isEqualTo("application/problem+json");
        assertThat(respuesta.getHeader("WWW-Authenticate")).isNull();
        var cuerpo = JSON.readTree(respuesta.getContentAsString());
        assertThat(cuerpo.get("code").asText()).isEqualTo("DESP_ERROR_RUTA_NO_ENCONTRADA");
        assertThat(cuerpo.get("correlationId").asText()).isEqualTo("correlacion-1");
        assertThat(cuerpo.get("instance").asText()).isEqualTo("/api/v1/zonas");
        assertThat(cuerpo.has("timestamp")).isTrue();
        assertThat(respuesta.getContentAsString()).doesNotContainIgnoringCase("gateway");
    }

    @Test
    void cabeceraIncorrectaRespondeNoEncontrado() throws Exception {
        for (String recibido : new String[] {"incorrecto", "", SECRETO + "x", "secreto"}) {
            var cadena = new MockFilterChain();
            var respuesta =
                    filtrar(
                            filtro(SECRETO, true),
                            solicitud("POST", "/api/v1/despachos", recibido),
                            cadena);
            assertThat(respuesta.getStatus()).as(recibido).isEqualTo(404);
            assertThat(cadena.getRequest()).as(recibido).isNull();
        }
    }

    @Test
    void cabeceraCorrectaContinua() throws Exception {
        assertContinua(filtro(SECRETO, true), solicitud("GET", "/api/v1/zonas", SECRETO));
    }

    @Test
    void dejaPasarRutasExcluidasSinCabecera() throws Exception {
        var filtro = filtro(SECRETO, true);
        assertContinua(filtro, solicitud("GET", "/actuator/health", null));
        assertContinua(filtro, solicitud("POST", "/internal/v1/despachos/d1/transiciones", null));
        assertContinua(filtro, solicitud("POST", "/api/v1/auth/token", null));
        assertContinua(filtro, solicitud("OPTIONS", "/api/v1/zonas", null));
    }

    @Test
    void noBloqueaMientrasNoSeaRequerido() throws Exception {
        assertContinua(filtro(SECRETO, false), solicitud("GET", "/api/v1/zonas", null));
        assertContinua(filtro("", false), solicitud("GET", "/api/v1/zonas", null));
        assertContinua(filtro(null, false), solicitud("GET", "/api/v1/zonas", "falso"));
    }

    @Test
    void requeridoSinSecretoFallaAlArrancar() {
        for (String secreto : new String[] {"", "   ", null}) {
            assertThatThrownBy(() -> filtro(secreto, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("INTERNO_SECRETO");
        }
    }

    private static FiltroAccesoInterno filtro(String secreto, boolean requerido) {
        return new FiltroAccesoInterno(secreto, requerido, JSON);
    }

    private static MockHttpServletRequest solicitud(String metodo, String ruta, String cabecera) {
        var solicitud = new MockHttpServletRequest(metodo, ruta);
        if (cabecera != null) {
            solicitud.addHeader(FiltroAccesoInterno.ENCABEZADO, cabecera);
        }
        return solicitud;
    }

    private static MockHttpServletResponse filtrar(
            FiltroAccesoInterno filtro, MockHttpServletRequest solicitud, MockFilterChain cadena)
            throws Exception {
        var respuesta = new MockHttpServletResponse();
        filtro.doFilter(solicitud, respuesta, cadena);
        return respuesta;
    }

    private static void assertContinua(FiltroAccesoInterno filtro, MockHttpServletRequest solicitud)
            throws Exception {
        var cadena = new MockFilterChain();
        var respuesta = filtrar(filtro, solicitud, cadena);
        assertThat(cadena.getRequest())
                .as(solicitud.getMethod() + " " + solicitud.getRequestURI())
                .isSameAs(solicitud);
        assertThat(respuesta.getStatus()).isEqualTo(200);
    }
}
