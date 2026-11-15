package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.geometrias.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Validaciones estructurales antes de entregar coordenadas a PostGIS. Nunca repara límites. */
@Component
public class ValidadorGeoJson {
    public static final int MAXIMO_POSICIONES = 200_000;

    public void validarCrs(JsonNode nodo) {
        if (!nodo.has("crs")) return;
        JsonNode crs = nodo.path("crs");
        String nombre = crs.path("properties").path("name").asText("");
        if (!"name".equals(crs.path("type").asText())
                || !Set.of(
                                "EPSG:4326",
                                "CRS84",
                                "urn:ogc:def:crs:OGC:1.3:CRS84",
                                "urn:ogc:def:crs:EPSG::4326")
                        .contains(nombre))
            throw invalido(
                    "Usa coordenadas longitud/latitud WGS84; transforma el CRS de origen antes de importar");
    }

    public int validarGeometria(JsonNode geometria) {
        if (geometria == null || !geometria.isObject())
            throw invalido("Falta una geometría no vacía");
        validarCrs(geometria);
        String tipo = geometria.path("type").asText("");
        JsonNode coordenadas = geometria.path("coordinates");
        if ("Polygon".equals(tipo)) return validarPoligono(coordenadas);
        if (!"MultiPolygon".equals(tipo)) throw invalido("Solo se admiten Polygon y MultiPolygon");
        noVacio(coordenadas);
        int posiciones = 0;
        for (JsonNode poligono : coordenadas) {
            posiciones += validarPoligono(poligono);
            limitar(posiciones);
        }
        return posiciones;
    }

    private int validarPoligono(JsonNode poligono) {
        noVacio(poligono);
        int posiciones = 0;
        for (JsonNode anillo : poligono) {
            if (!anillo.isArray() || anillo.size() < 4)
                throw invalido("Cada anillo debe tener al menos cuatro posiciones");
            for (JsonNode posicion : anillo) {
                if (!posicion.isArray()
                        || posicion.size() != 2
                        || !posicion.get(0).isNumber()
                        || !posicion.get(1).isNumber())
                    throw invalido("Cada posición debe contener longitud y latitud numéricas");
                double longitud = posicion.get(0).doubleValue();
                double latitud = posicion.get(1).doubleValue();
                if (!Double.isFinite(longitud)
                        || !Double.isFinite(latitud)
                        || longitud < -78
                        || longitud > -76
                        || latitud < -13.5
                        || latitud > -11)
                    throw invalido(
                            "Coordenadas fuera del ámbito plausible de Lima y Callao o no finitas");
                limitar(++posiciones);
            }
            JsonNode primero = anillo.get(0);
            JsonNode ultimo = anillo.get(anillo.size() - 1);
            if (primero.get(0).doubleValue() != ultimo.get(0).doubleValue()
                    || primero.get(1).doubleValue() != ultimo.get(1).doubleValue())
                throw invalido("Los anillos deben estar cerrados");
        }
        return posiciones;
    }

    private void noVacio(JsonNode nodo) {
        if (!nodo.isArray() || nodo.isEmpty())
            throw invalido("La geometría debe contener polígonos y anillos no vacíos");
    }

    public void limitar(int posiciones) {
        if (posiciones > MAXIMO_POSICIONES)
            throw invalido("El archivo supera 200 000 posiciones de coordenadas");
    }

    public static ErrorDeNegocio invalido(String mensaje) {
        return ErrorDeNegocio.de(400, "DESP_ERROR_IMPORTACION_INVALIDA", mensaje);
    }
}
