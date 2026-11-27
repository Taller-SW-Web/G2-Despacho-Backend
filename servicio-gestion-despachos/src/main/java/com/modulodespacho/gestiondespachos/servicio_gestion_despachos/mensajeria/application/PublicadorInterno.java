package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.application;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Publica un hecho ya confirmado en el outbox local hacia Operación. */
public interface PublicadorInterno {
    void publicar(UUID eventId, String tipo, JsonNode datos, Instant ocurridoEn);
}
