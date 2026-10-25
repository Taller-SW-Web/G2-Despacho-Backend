package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.SolicitudReprogramacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

/** Solo formato: la fecha futura y las precondiciones se validan después del replay. */
public final class SolicitudesReprogramacion {
    private SolicitudesReprogramacion() {}
    public static SolicitudReprogramacion leer(JsonNode cuerpo) {
        if (cuerpo == null || !cuerpo.isObject()) throw invalida("solicitud", "Debe ser un objeto JSON");
        var permitidos = Set.of("idIncidencia", "versionDespacho", "nuevaFechaProgramada", "observacion");
        for (String campo : cuerpo.propertyNames()) if (!permitidos.contains(campo)) throw invalida(campo, "Campo no admitido");
        var id = cuerpo.path("idIncidencia");
        if (!id.isString() || id.asString().isBlank()) throw invalida("idIncidencia", "Identificador obligatorio");
        var version = cuerpo.path("versionDespacho");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1)
            throw invalida("versionDespacho", "Debe ser un entero positivo");
        var datoFecha = cuerpo.path("nuevaFechaProgramada");
        if (!datoFecha.isString() || !datoFecha.asString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw invalida("nuevaFechaProgramada", "Usa una fecha civil YYYY-MM-DD");
        LocalDate fecha;
        try { fecha = LocalDate.parse(datoFecha.asString()); }
        catch (DateTimeParseException error) { throw invalida("nuevaFechaProgramada", "La fecha no existe"); }
        if (fecha.getYear() < 1) throw invalida("nuevaFechaProgramada", "El año debe estar entre 0001 y 9999");
        var texto = cuerpo.get("observacion");
        String observacion = null;
        if (texto != null && !texto.isNull()) {
            if (!texto.isString()) throw invalida("observacion", "Debe ser texto");
            observacion = normalizar(texto.asString());
            if (observacion != null && observacion.codePointCount(0, observacion.length()) > 500)
                throw invalida("observacion", "Admite como máximo 500 caracteres");
        }
        return new SolicitudReprogramacion(id.asString(), version.intValue(), fecha, observacion);
    }
    private static String normalizar(String texto) {
        int inicio = 0, fin = texto.length();
        while (inicio < fin && espacio(texto.codePointAt(inicio))) inicio += Character.charCount(texto.codePointAt(inicio));
        while (fin > inicio && espacio(texto.codePointBefore(fin))) fin -= Character.charCount(texto.codePointBefore(fin));
        return inicio == fin ? null : texto.substring(inicio, fin);
    }
    private static boolean espacio(int punto) {
        return Character.isWhitespace(punto) || Character.isSpaceChar(punto) || punto == 0xFEFF;
    }
    public static ErrorDeNegocio invalida(String campo, String motivo) {
        return ErrorDeNegocio.de(400, "DESP_ERROR_DATOS_REPROGRAMACION_INVALIDOS", "Revisa los datos de reprogramación",
                List.of(new ErrorDeCampo(campo, motivo)));
    }
}
