package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ModelosAdelanto {
    private ModelosAdelanto() {}

    public record Solicitud(String motivo) {}

    public record Respuesta(
            UUID idDespacho,
            String estado,
            LocalDate fechaProgramadaAnterior,
            LocalDate fechaProgramada,
            int versionDespacho,
            String motivo,
            Instant actualizadoEn) {}
}
