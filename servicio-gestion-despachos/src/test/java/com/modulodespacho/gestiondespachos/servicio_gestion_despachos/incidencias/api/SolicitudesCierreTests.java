package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static org.assertj.core.api.Assertions.*;

class SolicitudesCierreTests {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private String cuerpo(Object motivo, Object observacion) {
        var datos = new LinkedHashMap<String,Object>(); datos.put("idIncidencia","incidencia-opaca"); datos.put("versionDespacho",9);
        datos.put("motivo",motivo); datos.put("observacion",observacion); return json.writeValueAsString(datos);
    }
    @Test void normalizaEspaciosUnicodeYConservaSaltosYTextoLibre() {
        var solicitud = SolicitudesCierre.leer(json.readTree(cuerpo("\u00a0\uFEFFDecisión\noperativa\u3000", "\u001c ")));
        assertThat(solicitud.motivo()).isEqualTo("Decisión\noperativa"); assertThat(solicitud.observacion()).isNull();
        assertThat(SolicitudesCierre.leer(json.readTree(cuerpo("MAXIMO_INTENTOS_ALCANZADO", " Uno\ndos "))).observacion()).isEqualTo("Uno\ndos");
        assertThat(SolicitudesCierre.leer(json.readTree(cuerpo("Texto libre",null).replace(",\"observacion\":null", ""))).observacion()).isNull();
    }
    @Test void cuentaPuntosDeCodigoEnAmbosTextos() {
        var solicitud = SolicitudesCierre.leer(json.readTree(cuerpo("  "+"😀".repeat(500)+"  ","😀".repeat(500))));
        assertThat(solicitud.motivo()).isEqualTo("😀".repeat(500));
        assertThat(solicitud.observacion()).isEqualTo("😀".repeat(500));
        for (String invalido : List.of(cuerpo("😀".repeat(501),null),cuerpo("Cierre","😀".repeat(501)))) rechaza(invalido);
    }
    @Test void rechazaAusenciasTiposExtrasYVersionesSinCoercion() {
        String valido = cuerpo("Cierre", null);
        for (String invalido : List.of("[]", "null", "{}", cuerpo(null,null), cuerpo(" \u00a0",null), cuerpo(1,null), cuerpo(true,null), cuerpo("Cierre",false),
                valido.replace("\"versionDespacho\":9","\"versionDespacho\":\"9\""),valido.replace("\"versionDespacho\":9","\"versionDespacho\":9.0"),
                valido.replace("\"versionDespacho\":9","\"versionDespacho\":0"),valido.replace("\"versionDespacho\":9","\"versionDespacho\":2147483648"),
                valido.replace("\"idIncidencia\":\"incidencia-opaca\"","\"idIncidencia\":null"),valido.replace("}",",\"usuario\":\"otro\"}"))) rechaza(invalido);
    }
    private void rechaza(String cuerpo) {
        assertThatThrownBy(() -> SolicitudesCierre.leer(json.readTree(cuerpo))).isInstanceOf(ErrorDeNegocio.class)
                .extracting("estado","codigo").containsExactly(400,"DESP_ERROR_DATOS_CIERRE_INVALIDOS");
    }
}
