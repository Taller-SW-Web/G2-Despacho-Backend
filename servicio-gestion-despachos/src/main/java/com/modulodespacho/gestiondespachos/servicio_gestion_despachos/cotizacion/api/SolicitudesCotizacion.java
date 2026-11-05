package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.api;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.SolicitudCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

/** Evita coerciones de SKU/cantidad y conserva cobertura antes de validar las líneas. */
final class SolicitudesCotizacion {
    private SolicitudesCotizacion() {}

    static SolicitudCotizacion desdeJson(JsonNode cuerpo) {
        if (cuerpo == null || cuerpo.isNull()) return new SolicitudCotizacion(null, null);
        if (!cuerpo.isObject()) throw ErrorDeNegocio.de(400, CodigosError.SOLICITUD_INVALIDA,
                "La solicitud debe ser un objeto JSON");
        JsonNode destino = cuerpo.path("destino");
        var direccion = destino.isObject() ? new SolicitudCotizacion.Destino(
                textoOpcional(destino.path("direccion")), textoOpcional(destino.path("distrito")),
                textoOpcional(destino.path("codigoPostal"))) : null;
        JsonNode recibidas = cuerpo.path("lineas");
        if (recibidas.isMissingNode() || recibidas.isNull()) return new SolicitudCotizacion(direccion, null);
        if (!recibidas.isArray()) {
            return new SolicitudCotizacion(direccion, List.of(new SolicitudCotizacion.Linea(null, null)));
        }
        List<SolicitudCotizacion.Linea> lineas = new ArrayList<>();
        for (JsonNode linea : recibidas) {
            JsonNode sku = linea.path("sku");
            JsonNode cantidad = linea.path("cantidad");
            lineas.add(new SolicitudCotizacion.Linea(sku.isString() ? sku.asString() : null,
                    cantidad.isIntegralNumber() && cantidad.canConvertToInt() ? cantidad.intValue() : null));
        }
        return new SolicitudCotizacion(direccion, lineas);
    }

    private static String textoOpcional(JsonNode valor) {
        if (valor.isMissingNode() || valor.isNull()) return null;
        if (!valor.isString()) throw ErrorDeNegocio.de(400, CodigosError.CAMPOS_INVALIDOS,
                "Los campos del destino deben ser texto");
        return valor.asString();
    }
}
