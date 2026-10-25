package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static org.assertj.core.api.Assertions.*;

class SolicitudesReprogramacionTests {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private String cuerpo(String fecha, String observacion) {
        return "{\"idIncidencia\":\"incidencia-opaca\",\"versionDespacho\":9,\"nuevaFechaProgramada\":"
                + json.writeValueAsString(fecha) + ",\"observacion\":" + json.writeValueAsString(observacion) + "}";
    }
    @Test void conservaDiaCivilYNormalizaUnicodeSinEliminarSaltosInternos() {
        var solicitud = SolicitudesReprogramacion.leer(json.readTree(cuerpo("2026-10-25", "\u00a0\uFEFFPrimera\nsegunda\u3000")));
        assertThat(solicitud.nuevaFechaProgramada().toString()).isEqualTo("2026-10-25");
        assertThat(solicitud.observacion()).isEqualTo("Primera\nsegunda");
        assertThat(SolicitudesReprogramacion.leer(json.readTree(cuerpo("0001-01-01", " \u00a0"))).observacion()).isNull();
        assertThat(SolicitudesReprogramacion.leer(json.readTree(cuerpo("9999-12-31", null))).nuevaFechaProgramada().getYear()).isEqualTo(9999);
    }
    @Test void cuentaPuntosDeCodigoEnLugarDeUnidadesUtf16() {
        assertThat(SolicitudesReprogramacion.leer(json.readTree(cuerpo("2026-10-25", "  " + "😀".repeat(500) + "  "))).observacion())
                .isEqualTo("😀".repeat(500));
        assertThatThrownBy(() -> SolicitudesReprogramacion.leer(json.readTree(cuerpo("2026-10-25", "😀".repeat(501)))))
                .isInstanceOf(ErrorDeNegocio.class).extracting("estado", "codigo")
                .containsExactly(400, "DESP_ERROR_DATOS_REPROGRAMACION_INVALIDOS");
    }
    @Test void rechazaFechasInexistentesFormatosYTiposSinCoercion() {
        for (String fecha : new String[]{"2026-02-29", "2026-04-31", "2026-1-01", "0000-01-01", "10000-01-01",
                "2026-10-25T00:00:00Z", " 2026-10-25", ""}) {
            assertThatThrownBy(() -> SolicitudesReprogramacion.leer(json.readTree(cuerpo(fecha, null)))).isInstanceOf(ErrorDeNegocio.class);
        }
        String valido = cuerpo("2026-10-25", null);
        for (String invalido : new String[]{"[]", "null", "{}", valido.replace("\"versionDespacho\":9", "\"versionDespacho\":\"9\""),
                valido.replace("\"versionDespacho\":9", "\"versionDespacho\":9.0"), valido.replace("\"versionDespacho\":9", "\"versionDespacho\":0"),
                valido.replace("\"versionDespacho\":9", "\"versionDespacho\":2147483648"),
                valido.replace("\"observacion\":null", "\"observacion\":false"),
                valido.replace("\"idIncidencia\":\"incidencia-opaca\"", "\"idIncidencia\":null"),
                valido.replace("}", ",\"ejecutor\":\"otro\"}")}) {
            assertThatThrownBy(() -> SolicitudesReprogramacion.leer(json.readTree(invalido))).isInstanceOf(ErrorDeNegocio.class);
        }
    }
}
