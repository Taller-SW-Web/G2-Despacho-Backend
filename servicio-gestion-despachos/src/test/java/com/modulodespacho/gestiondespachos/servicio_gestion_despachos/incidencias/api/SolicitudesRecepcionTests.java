package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static org.assertj.core.api.Assertions.*;

class SolicitudesRecepcionTests {
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void aceptaNoYNormalizaObservacionSinHacerlaObligatoria() {
        for (String extra : new String[]{"", ",\"observacion\":null", ",\"observacion\":\"  \\n  \""}) {
            var solicitud = SolicitudesRecepcion.leer(json.readTree("""
                    {"idIncidencia":"INC-1","versionDespacho":8,"selloIntacto":false%s}
                    """.formatted(extra)));
            assertThat(solicitud.selloIntacto()).isFalse();
            assertThat(solicitud.observacion()).isNull();
        }
        assertThat(SolicitudesRecepcion.leer(json.readTree(cuerpo("  línea 1\\nlínea 2  "))).observacion()).isEqualTo("línea 1\nlínea 2");
        assertThat(SolicitudesRecepcion.leer(json.readTree(cuerpo("\u00a0\u2003\ufeff"))).observacion()).isNull();
    }
    @Test void cuentaPuntosDeCodigoEnVezDeUnidadesUtf16() {
        assertThat(SolicitudesRecepcion.leer(json.readTree(cuerpo("😀".repeat(500)))).observacion()).hasSize(1000);
        assertThatThrownBy(() -> SolicitudesRecepcion.leer(json.readTree(cuerpo("😀".repeat(501)))))
                .isInstanceOfSatisfying(ErrorDeNegocio.class, e -> assertThat(e.getErrores().getFirst().campo()).isEqualTo("observacion"));
    }
    @Test void rechazaCoercionesIdentidadIncompletaExtrasYCuerposInvalidos() {
        for (String cuerpo : new String[] {"null", "[]", "{}", """
                {"idIncidencia":"inc","versionDespacho":1,"selloIntacto":"false"}
                """, """
                {"idIncidencia":"inc","versionDespacho":1,"selloIntacto":null}
                """, """
                {"idIncidencia":"inc","versionDespacho":1,"selloIntacto":0}
                """, """
                {"idIncidencia":"inc","versionDespacho":1.0,"selloIntacto":false}
                """, """
                {"idIncidencia":"inc","versionDespacho":2147483648,"selloIntacto":false}
                """, """
                {"idIncidencia":" ","versionDespacho":1,"selloIntacto":false}
                """, """
                {"idIncidencia":"inc","versionDespacho":1,"selloIntacto":false,"idUsuario":"ajeno"}
                """, """
                {"idIncidencia":"inc","versionDespacho":1,"selloIntacto":false,"observacion":false}
                """}) {
            assertThatThrownBy(() -> SolicitudesRecepcion.leer(json.readTree(cuerpo))).isInstanceOfSatisfying(ErrorDeNegocio.class, e -> {
                assertThat(e.getCodigo()).isEqualTo("DESP_ERROR_DATOS_RECEPCION_INVALIDOS");
                assertThat(e.getEstado()).isEqualTo(400);
                assertThat(e.getErrores()).isNotEmpty();
            });
        }
    }
    private String cuerpo(String texto) {
        return "{\"idIncidencia\":\"inc\",\"versionDespacho\":8,\"selloIntacto\":false,\"observacion\":\"" + texto + "\"}";
    }
}
