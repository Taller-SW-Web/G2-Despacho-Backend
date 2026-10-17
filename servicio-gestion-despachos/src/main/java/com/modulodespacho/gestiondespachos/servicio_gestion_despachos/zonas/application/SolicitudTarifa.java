package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.math.BigDecimal;

public record SolicitudTarifa(
        BigDecimal tarifaBase,
        BigDecimal pesoIncluidoKg,
        BigDecimal recargoKgAdicional,
        BigDecimal factorVolumetrico,
        String moneda,
        Integer plazoEstimadoDiasHabiles) {
}
