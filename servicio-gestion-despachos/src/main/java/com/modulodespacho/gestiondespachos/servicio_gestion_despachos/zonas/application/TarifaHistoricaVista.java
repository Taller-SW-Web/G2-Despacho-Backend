package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.time.Instant;
import java.util.UUID;

public record TarifaHistoricaVista(UUID id, boolean vigente, TarifaVista condiciones,
        UUID creadoPor, Instant creadoEn, Instant actualizadoEn) {}
