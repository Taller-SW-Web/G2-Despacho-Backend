package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.ResultadoCotizacion;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RespuestaCotizacion(
        boolean coberturaDisponible, String tipoCotizacion,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nombreZona,
        String direccion, String distrito, String codigoPostal,
        BigDecimal pesoTotalKg, BigDecimal volumenTotalM3, BigDecimal costoEnvio,
        String moneda, Integer plazoEstimadoDiasHabiles, LocalDate fechaEstimadaEntrega,
        Instant cotizadoEn, String mensaje) {

    public static RespuestaCotizacion desde(ResultadoCotizacion resultado) {
        return new RespuestaCotizacion(resultado.coberturaDisponible(), resultado.tipoCotizacion(),
                resultado.nombreZona(), resultado.direccion(), resultado.distrito(), resultado.codigoPostal(),
                resultado.pesoTotalKg(), resultado.volumenTotalM3(), resultado.costoEnvio(), resultado.moneda(),
                resultado.plazoEstimadoDiasHabiles(), resultado.fechaEstimadaEntrega(),
                resultado.cotizadoEn(), resultado.mensaje());
    }
}
