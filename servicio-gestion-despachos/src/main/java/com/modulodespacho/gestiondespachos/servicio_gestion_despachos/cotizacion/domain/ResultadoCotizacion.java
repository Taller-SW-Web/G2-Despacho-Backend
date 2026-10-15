package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ResultadoCotizacion(
        boolean coberturaDisponible, String tipoCotizacion, String nombreZona,
        String direccion, String distrito, String codigoPostal,
        BigDecimal pesoTotalKg, BigDecimal volumenTotalM3, BigDecimal costoEnvio,
        String moneda, Integer plazoEstimadoDiasHabiles, LocalDate fechaEstimadaEntrega,
        Instant cotizadoEn, String mensaje) {
}
