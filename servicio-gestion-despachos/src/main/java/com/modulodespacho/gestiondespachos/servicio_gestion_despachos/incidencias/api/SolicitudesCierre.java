package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.SolicitudCierre;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

public final class SolicitudesCierre {
    private SolicitudesCierre() {}
    public static SolicitudCierre leer(JsonNode cuerpo) {
        if (cuerpo == null || !cuerpo.isObject()) throw invalida("solicitud", "Debe ser un objeto JSON");
        var permitidos = Set.of("idIncidencia", "versionDespacho", "motivo", "observacion");
        for (String campo : cuerpo.propertyNames()) if (!permitidos.contains(campo)) throw invalida(campo, "Campo no admitido");
        var id = cuerpo.path("idIncidencia");
        if (!id.isString() || id.asString().isBlank()) throw invalida("idIncidencia", "Identificador obligatorio");
        var version = cuerpo.path("versionDespacho");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1)
            throw invalida("versionDespacho", "Debe ser un entero positivo");
        String motivo = texto(cuerpo.get("motivo"), "motivo");
        if (motivo == null) throw invalida("motivo", "Indica el motivo del cierre");
        return new SolicitudCierre(id.asString(), version.intValue(), motivo, texto(cuerpo.get("observacion"), "observacion"));
    }
    private static String texto(JsonNode dato, String campo) {
        if (dato == null || dato.isNull()) return null;
        if (!dato.isString()) throw invalida(campo, "Debe ser texto");
        String texto = dato.asString();
        int inicio = 0, fin = texto.length();
        while (inicio < fin && espacio(texto.codePointAt(inicio))) inicio += Character.charCount(texto.codePointAt(inicio));
        while (fin > inicio && espacio(texto.codePointBefore(fin))) fin -= Character.charCount(texto.codePointBefore(fin));
        if (texto.codePointCount(inicio, fin) > 500) throw invalida(campo, "Admite como máximo 500 caracteres");
        return inicio == fin ? null : texto.substring(inicio, fin);
    }
    private static boolean espacio(int punto) {
        return Character.isWhitespace(punto) || Character.isSpaceChar(punto) || punto == 0xFEFF;
    }
    public static ErrorDeNegocio invalida(String campo, String motivo) {
        return ErrorDeNegocio.de(400, "DESP_ERROR_DATOS_CIERRE_INVALIDOS", "Revisa los datos del cierre",
                List.of(new ErrorDeCampo(campo, motivo)));
    }
}
