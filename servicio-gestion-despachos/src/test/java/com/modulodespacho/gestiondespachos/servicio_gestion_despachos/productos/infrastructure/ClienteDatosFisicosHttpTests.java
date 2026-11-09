package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.productos.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(OutputCaptureExtension.class)
class ClienteDatosFisicosHttpTests {
    private MockRestServiceServer servidor;
    private ClienteDatosFisicosHttp cliente;

    @BeforeEach
    void preparar() {
        RestClient.Builder constructor = RestClient.builder().baseUrl("https://productos.prueba");
        servidor = MockRestServiceServer.bindTo(constructor).build();
        cliente = new ClienteDatosFisicosHttp(constructor.build(), () -> "credencial-propia");
    }

    @Test
    void consultaUnaVezCadaIdentidadOpacaYConservaEstadoYPrecision() {
        servidor.expect(
                        requestTo(
                                "https://productos.prueba/api/v1/productos/datos-fisicos/consulta"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer credencial-propia"))
                .andExpect(content().json("{\"skus\":[\"001-a\",\"001-A\"]}"))
                .andRespond(
                        withSuccess(
                                lote(
                                        perfil("001-A", "BORRADOR")
                                                + ","
                                                + perfil("001-a", "INACTIVO")),
                                MediaType.APPLICATION_JSON));
        var datos = cliente.obtener(List.of("001-a", "001-A", "001-a"));
        assertThat(datos.keySet()).containsExactly("001-a", "001-A");
        assertThat(datos.get("001-A").estado()).isEqualTo("BORRADOR");
        assertThat(datos.get("001-a").pesoKg()).isEqualByComparingTo("0.123456789123456789");
        assertThat(datos.get("001-a").actualizadoEn()).isNotNull();
        servidor.verify();
    }

    @Test
    void validaTodoElLoteAntesDeInterpretarInexistentes() {
        for (String respuesta :
                List.of(
                        "{}",
                        "{",
                        lote(perfil("A", "ACTIVO")),
                        lote(perfil("A", "ACTIVO") + "," + perfil("A", "ACTIVO")),
                        lote(perfil("A", "ACTIVO") + "," + perfil("OTRO", "ACTIVO")),
                        "{\"productos\":["
                                + perfil("A", "ACTIVO")
                                + "],\"noEncontrados\":[\"A\",\"B\"]}",
                        "{\"productos\":[],\"noEncontrados\":[\"A\",\"A\"]}",
                        "{\"productos\":["
                                + perfil("A", "DESCONOCIDO")
                                + "],\"noEncontrados\":[\"B\"]}",
                        "{\"productos\":["
                                + perfil("A", "ACTIVO").replace("2026-11-05T12:00:00Z", "invalida")
                                + "],\"noEncontrados\":[\"B\"]}",
                        "{\"productos\":["
                                + perfil("A", "ACTIVO").replace("0.123456789123456789", "\"1\"")
                                + "],\"noEncontrados\":[\"B\"]}")) {
            preparar();
            responder(200, respuesta);
            comprobarError(502, "DESP_ERROR_RESPUESTA_PRODUCTOS_INVALIDA", List.of("A", "B"));
        }
    }

    @Test
    void inexistentesSeObtienenSoloDeUnaClasificacionIntegra() {
        responder(200, "{\"productos\":[" + perfil("A", "ACTIVO") + "],\"noEncontrados\":[\"B\"]}");
        assertThatThrownBy(() -> cliente.obtener(List.of("B", "A")))
                .isInstanceOfSatisfying(
                        ErrorDeNegocio.class,
                        error -> {
                            assertThat(error.getEstado()).isEqualTo(422);
                            assertThat(error.getCodigo())
                                    .isEqualTo("DESP_ERROR_PRODUCTO_NO_ENCONTRADO");
                            assertThat(error.getSkusAfectados()).containsExactly("B");
                        });
    }

    @Test
    void traduceSoloErroresHomologadosConIdentificacionValida() {
        for (String codigo : List.of("DATOS_FISICOS_INCOMPLETOS", "SKU_INVALIDO")) {
            preparar();
            responder(422, problema(codigo, "[\"001-A\",\"B\"]"));
            assertThatThrownBy(() -> cliente.obtener(List.of("001-A", "C", "B")))
                    .isInstanceOfSatisfying(
                            ErrorDeNegocio.class,
                            error -> {
                                assertThat(error.getEstado()).isEqualTo(422);
                                assertThat(error.getCodigo())
                                        .isEqualTo(
                                                codigo.equals("SKU_INVALIDO")
                                                        ? "DESP_ERROR_PRODUCTO_NO_ELEGIBLE"
                                                        : "DESP_ERROR_DATOS_FISICOS_INCOMPLETOS");
                                assertThat(error.getSkusAfectados()).containsExactly("001-A", "B");
                            });
        }
    }

    @Test
    void rechazaDetallesAusentesVaciosAjenoDuplicadoOMalOrdenados() {
        for (String respuesta :
                List.of(
                        "{\"status\":422,\"code\":\"SKU_INVALIDO\"}",
                        problema("SKU_INVALIDO", "[]"),
                        problema("SKU_INVALIDO", "[1]"),
                        problema("SKU_INVALIDO", "[\"A\",\"A\"]"),
                        problema("SKU_INVALIDO", "[\"ajeno\"]"),
                        problema("SKU_INVALIDO", "[\"B\",\"A\"]"),
                        problema("OTRO_ERROR", "[\"A\"]"),
                        problema("DATOS_FISICOS_INCOMPLETOS", "null"))) {
            preparar();
            responder(422, respuesta);
            comprobarError(502, "DESP_ERROR_RESPUESTA_PRODUCTOS_INVALIDA", List.of("A", "B"));
        }
    }

    @Test
    void erroresTecnicosYDeCredencialSalienteSonIndisponibilidad(CapturedOutput salida) {
        for (int estado : List.of(401, 403, 500, 503)) {
            preparar();
            responder(estado, "{}");
            comprobarError(503, "DESP_ERROR_PRODUCTOS_NO_DISPONIBLE", List.of("A"));
        }
        assertThat(salida.getAll())
                .contains(
                        "motivo=credencial_rechazada estado_productos=401",
                        "motivo=error_del_proveedor estado_productos=503")
                .doesNotContain("credencial-propia");
    }

    @Test
    void conservaLaCorrelacionYRegistraTiempoAgotadoSinDatosSensibles(CapturedOutput salida) {
        String correlacion = "35ac19fa-4b25-4a98-bbdd-882234ec1a2c";
        var solicitud = new MockHttpServletRequest();
        solicitud.setAttribute(FiltroCorrelacion.ATRIBUTO, correlacion);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(solicitud));
        try {
            servidor.expect(
                            requestTo(
                                    "https://productos.prueba/api/v1/productos/datos-fisicos/consulta"))
                    .andExpect(header(FiltroCorrelacion.ENCABEZADO, correlacion))
                    .andRespond(withException(new HttpTimeoutException("mensaje-reservado")));
            comprobarError(503, "DESP_ERROR_PRODUCTOS_NO_DISPONIBLE", List.of("A"));
            assertThat(salida.getAll())
                    .contains("correlacion=" + correlacion + " motivo=tiempo_agotado")
                    .doesNotContain("mensaje-reservado", "credencial-propia");
            servidor.verify();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void rechazaCientoUnSkuAntesDeEmitirHttp() {
        comprobarError(
                400,
                "DESP_ERROR_LIMITE_SKUS_EXCEDIDO",
                java.util.stream.IntStream.range(0, 101).mapToObj(i -> "SKU-" + i).toList());
        servidor.verify();
    }

    private void comprobarError(int estado, String codigo, List<String> skus) {
        assertThatThrownBy(() -> cliente.obtener(skus))
                .isInstanceOfSatisfying(
                        ErrorDeNegocio.class,
                        error -> {
                            assertThat(error.getEstado()).isEqualTo(estado);
                            assertThat(error.getCodigo()).isEqualTo(codigo);
                        });
    }

    private void responder(int estado, String cuerpo) {
        servidor.expect(
                        requestTo(
                                "https://productos.prueba/api/v1/productos/datos-fisicos/consulta"))
                .andRespond(
                        withStatus(HttpStatus.valueOf(estado))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(cuerpo));
    }

    private static String lote(String perfiles) {
        return "{\"productos\":[" + perfiles + "],\"noEncontrados\":[]}";
    }

    private static String problema(String codigo, String afectados) {
        return "{\"status\":422,\"code\":\""
                + codigo
                + "\",\"details\":{\"skusAfectados\":"
                + afectados
                + "}}";
    }

    private static String perfil(String sku, String estado) {
        return """
                {"sku":"%s","estado":"%s","actualizadoEn":"2026-11-05T12:00:00Z",
                 "pesoKg":0.123456789123456789,"dimensionesCm":{"largo":10,"ancho":20,"alto":30}}
                """
                .formatted(sku, estado);
    }
}
