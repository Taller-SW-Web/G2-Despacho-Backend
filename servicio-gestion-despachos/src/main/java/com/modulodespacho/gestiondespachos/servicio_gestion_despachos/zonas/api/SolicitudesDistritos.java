package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.api;

import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionDistritos.invalido;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.SolicitudDistrito;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoDistrito;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/** Valida tipos y campos explícitos para impedir cambios de estado/identidad por coerción JSON. */
@Component
public class SolicitudesDistritos {
    public SolicitudDistrito datos(JsonNode cuerpo, boolean edicion) {
        objeto(
                cuerpo,
                edicion
                        ? Set.of("nombre", "provincia", "departamento", "ubigeo", "versionDistrito")
                        : Set.of("nombre", "provincia", "departamento", "ubigeo"));
        return new SolicitudDistrito(
                texto(cuerpo, "nombre"),
                texto(cuerpo, "provincia"),
                texto(cuerpo, "departamento"),
                texto(cuerpo, "ubigeo"),
                edicion ? version(cuerpo) : null);
    }

    public CambioEstado estado(JsonNode cuerpo) {
        objeto(cuerpo, Set.of("estado", "versionDistrito"));
        String estado = texto(cuerpo, "estado");
        try {
            return new CambioEstado(EstadoDistrito.valueOf(estado), version(cuerpo));
        } catch (IllegalArgumentException error) {
            throw invalido("estado", "Selecciona ACTIVO o INACTIVO");
        }
    }

    public void parametros(MultiValueMap<String, String> parametros, Set<String> permitidos) {
        parametros.forEach(
                (campo, valores) -> {
                    if (!permitidos.contains(campo)
                            || valores.size() != 1
                            || valores.getFirst().isBlank())
                        throw invalido(campo, "Parámetro desconocido, repetido o vacío");
                });
    }

    public EstadoDistrito filtroEstado(String estado) {
        if (estado == null) return EstadoDistrito.ACTIVO;
        if ("TODOS".equals(estado)) return null;
        try {
            return EstadoDistrito.valueOf(estado);
        } catch (IllegalArgumentException error) {
            throw invalido("estado", "Selecciona ACTIVO, INACTIVO o TODOS");
        }
    }

    public long versionParametro(String valor) {
        if (valor == null || !valor.matches("[0-9]+"))
            throw invalido("versionDistrito", "Indica una versión entera no negativa");
        try {
            return Long.parseLong(valor);
        } catch (NumberFormatException error) {
            throw invalido("versionDistrito", "La versión está fuera de rango");
        }
    }

    private Long version(JsonNode cuerpo) {
        JsonNode valor = cuerpo.get("versionDistrito");
        if (valor == null
                || !valor.isIntegralNumber()
                || !valor.canConvertToLong()
                || valor.longValue() < 0)
            throw invalido("versionDistrito", "Indica una versión entera no negativa");
        return valor.longValue();
    }

    private String texto(JsonNode cuerpo, String campo) {
        JsonNode valor = cuerpo.get(campo);
        if (valor == null || !valor.isTextual())
            throw invalido(campo, "El campo debe ser una cadena obligatoria");
        return valor.asText();
    }

    private void objeto(JsonNode cuerpo, Set<String> permitidos) {
        if (cuerpo == null || !cuerpo.isObject())
            throw invalido("solicitud", "El cuerpo debe ser un objeto JSON");
        cuerpo.properties()
                .forEach(
                        entrada -> {
                            if (!permitidos.contains(entrada.getKey()))
                                throw invalido(entrada.getKey(), "Campo no permitido");
                        });
    }

    public record CambioEstado(EstadoDistrito estado, Long versionDistrito) {}
}
