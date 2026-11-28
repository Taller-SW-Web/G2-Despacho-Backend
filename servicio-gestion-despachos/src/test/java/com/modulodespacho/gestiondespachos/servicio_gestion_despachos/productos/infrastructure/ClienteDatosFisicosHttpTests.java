package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.productos.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import java.io.IOException;
import java.net.ConnectException;
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
        preparar(false, "credencial-propia");
    }

    private void preparar(boolean respaldo, String token) {
        RestClient.Builder constructor = RestClient.builder().baseUrl("https://productos.prueba");
        servidor = MockRestServiceServer.bindTo(constructor).build();
        cliente = new ClienteDatosFisicosHttp(constructor.build(), () -> token, respaldo);
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
    void completaSoloLosSkuAusentesYConservaLosPerfilesReales(CapturedOutput salida) {
        preparar(true, "credencial-propia");
        responder(
                200,
                "{\"productos\":[" + perfil("A", "INACTIVO") + "],\"noEncontrados\":[\"001-b\"]}");

        var datos = cliente.obtener(List.of("001-b", "A", "001-b"));

        assertThat(datos.keySet()).containsExactly("001-b", "A");
        assertThat(datos.get("A").simulado()).isFalse();
        assertThat(datos.get("A").estado()).isEqualTo("INACTIVO");
        assertThat(datos.get("A").pesoKg()).isEqualByComparingTo("0.123456789123456789");
        var provisional = datos.get("001-b");
        assertThat(provisional.simulado()).isTrue();
        assertThat(provisional.pesoKg()).isEqualByComparingTo("1");
        assertThat(provisional.largoCm()).isEqualByComparingTo("20");
        assertThat(provisional.anchoCm()).isEqualByComparingTo("15");
        assertThat(provisional.altoCm()).isEqualByComparingTo("10");
        assertThat(provisional.estado()).isEqualTo("ACTIVO");
        assertThat(salida.getAll()).contains("motivo=sku_no_encontrado cantidad_skus=1");
        servidor.verify();
    }

    @Test
    void completaTodoElLoteAnteUnErrorTecnicoDelProveedor() {
        preparar(true, "credencial-propia");
        responder(503, "{}");

        var datos = cliente.obtener(List.of("A", "B"));

        assertThat(datos.keySet()).containsExactly("A", "B");
        assertThat(datos.values()).allSatisfy(dato -> assertThat(dato.simulado()).isTrue());
        servidor.verify();
    }

    @Test
    void completaTodoElLoteAnteUnTimeoutOFalloDeConexion() {
        for (IOException causa :
                List.of(new HttpTimeoutException("prueba"), new ConnectException("prueba"))) {
            preparar(true, "credencial-propia");
            servidor.expect(
                            requestTo(
                                    "https://productos.prueba/api/v1/productos/datos-fisicos/consulta"))
                    .andRespond(withException(causa));

            var datos = cliente.obtener(List.of("A", "B"));

            assertThat(datos.keySet()).containsExactly("A", "B");
            assertThat(datos.values()).allSatisfy(dato -> assertThat(dato.simulado()).isTrue());
            servidor.verify();
        }
    }

    @Test
    void conservaLosErroresDeCredencialesConElRespaldoHabilitado() {
        preparar(true, "");
        comprobarError(503, "DESP_ERROR_PRODUCTOS_NO_DISPONIBLE", List.of("A"));
        servidor.verify();
        for (int estado : List.of(401, 403)) {
            preparar(true, "credencial-propia");
            responder(estado, "{}");
            comprobarError(503, "DESP_ERROR_PRODUCTOS_NO_DISPONIBLE", List.of("A"));
            servidor.verify();
        }
    }

    @Test
    void conservaLosErroresDeNegocioYDeContratoConElRespaldoHabilitado() {
        preparar(true, "credencial-propia");
        responder(422, problema("SKU_INVALIDO", "[\"A\"]"));
        comprobarError(422, "DESP_ERROR_PRODUCTO_NO_ELEGIBLE", List.of("A"));
        servidor.verify();

        preparar(true, "credencial-propia");
        responder(422, problema("DATOS_FISICOS_INCOMPLETOS", "[\"A\"]"));
        comprobarError(422, "DESP_ERROR_DATOS_FISICOS_INCOMPLETOS", List.of("A"));
        servidor.verify();

        preparar(true, "credencial-propia");
        responder(200, "{\"productos\":[],\"noEncontrados\":[\"A\"]}");
        comprobarError(502, "DESP_ERROR_RESPUESTA_PRODUCTOS_INVALIDA", List.of("A", "B"));
        servidor.verify();
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
