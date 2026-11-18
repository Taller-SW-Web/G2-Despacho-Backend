package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.geometrias.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface RepositorioGeometrias {
    record DistritoGeografico(UUID id, long versionDistrito, JsonNode entidad) {}

    record Validacion(UUID id, Instant expiraEn, JsonNode propuesta, boolean consumida) {}

    List<DistritoGeografico> catalogo(String nombre, String estado, UUID zona);

    Optional<JsonNode> zona(UUID id);

    String motivoInvalidez(JsonNode geometria);

    void guardarValidacion(UUID id, String ejecutor, Instant expiraEn, JsonNode propuesta);

    Optional<Validacion> buscarValidacion(UUID id, String ejecutor);

    void registrarImportacion(
            UUID id,
            UUID validacion,
            String fuente,
            String versionFuente,
            String nombreArchivo,
            String ejecutor,
            Instant ahora);

    void guardarGeometria(
            UUID distrito,
            JsonNode geometria,
            long version,
            String fuente,
            String versionFuente,
            UUID importacion,
            String ejecutor,
            Instant ahora,
            String huella);
}
