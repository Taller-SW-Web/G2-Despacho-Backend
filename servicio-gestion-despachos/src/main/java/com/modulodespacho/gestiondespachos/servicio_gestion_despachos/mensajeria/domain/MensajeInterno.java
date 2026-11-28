package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.domain;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Sobre común de los eventos internos entre Gestión y Operación (contrato §3.6). */
public record MensajeInterno(
        UUID eventId,
        String tipo,
        int version,
        UUID claveIdempotencia,
        Instant ocurridoEn,
        JsonNode datos) {}
