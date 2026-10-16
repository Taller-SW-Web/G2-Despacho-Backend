package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.math.BigDecimal;
import java.time.Instant;

public record TarifaVista(
        BigDecimal tarifaBase,
        BigDecimal pesoIncluidoKg,
        BigDecimal recargoKgAdicional,
        BigDecimal factorVolumetrico,
        String moneda,
        Integer plazoEstimadoDiasHabiles,
        Instant actualizadoEn) {
}
