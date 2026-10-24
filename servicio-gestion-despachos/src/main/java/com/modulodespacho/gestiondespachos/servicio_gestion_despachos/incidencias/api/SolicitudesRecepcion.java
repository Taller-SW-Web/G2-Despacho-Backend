package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.SolicitudRecepcion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

/** Valida tipos sin coerciones y normaliza antes de identificar una repetición. */
public final class SolicitudesRecepcion {
    private SolicitudesRecepcion() {}
    public static SolicitudRecepcion leer(JsonNode cuerpo) {
        if (cuerpo == null || !cuerpo.isObject()) throw invalida("solicitud", "Debe ser un objeto JSON");
        var permitidos = Set.of("idIncidencia", "versionDespacho", "selloIntacto", "observacion");
        for (String campo : cuerpo.propertyNames()) {
            if (!permitidos.contains(campo)) throw invalida(campo, "Campo no admitido");
        }
        var id = cuerpo.path("idIncidencia");
        if (!id.isString() || id.asString().isBlank()) throw invalida("idIncidencia", "Identificador obligatorio");
        var version = cuerpo.path("versionDespacho");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1)
            throw invalida("versionDespacho", "Debe ser un entero positivo");
        var sello = cuerpo.path("selloIntacto");
        if (!sello.isBoolean()) throw invalida("selloIntacto", "Selecciona Sí o No");
        var texto = cuerpo.get("observacion");
        String observacion = null;
        if (texto != null && !texto.isNull()) {
            if (!texto.isString()) throw invalida("observacion", "Debe ser texto");
            observacion = recortar(texto.asString());
            if (observacion.codePointCount(0, observacion.length()) > 500)
                throw invalida("observacion", "Admite como máximo 500 caracteres");
            if (observacion.isEmpty()) observacion = null;
        }
        return new SolicitudRecepcion(id.asString(), version.intValue(), sello.asBoolean(), observacion);
    }
    private static String recortar(String texto) {
        int inicio = 0, fin = texto.length();
        while (inicio < fin && espacio(texto.codePointAt(inicio))) inicio += Character.charCount(texto.codePointAt(inicio));
        while (fin > inicio && espacio(texto.codePointBefore(fin))) fin -= Character.charCount(texto.codePointBefore(fin));
        return texto.substring(inicio, fin);
    }
    private static boolean espacio(int punto) {
        return Character.isWhitespace(punto) || Character.isSpaceChar(punto) || punto == 0xFEFF;
    }
    public static ErrorDeNegocio invalida(String campo, String motivo) {
        return ErrorDeNegocio.de(400, "DESP_ERROR_DATOS_RECEPCION_INVALIDOS", "Revisa los datos de recepción",
                List.of(new ErrorDeCampo(campo, motivo)));
    }
}
