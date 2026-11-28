package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Datos físicos de un producto usados para calcular su peso y volumen. */
public record DatosFisicos(
        BigDecimal pesoKg,
        BigDecimal largoCm,
        BigDecimal anchoCm,
        BigDecimal altoCm,
        String estado,
        Instant actualizadoEn,
        boolean simulado) {

    private static final BigDecimal CENTIMETROS_POR_METRO = new BigDecimal("100");

    public DatosFisicos(
            BigDecimal pesoKg,
            BigDecimal largoCm,
            BigDecimal anchoCm,
            BigDecimal altoCm,
            String estado,
            Instant actualizadoEn) {
        this(pesoKg, largoCm, anchoCm, altoCm, estado, actualizadoEn, false);
    }

    public boolean completo() {
        return esPositivo(pesoKg)
                && esPositivo(largoCm)
                && esPositivo(anchoCm)
                && esPositivo(altoCm);
    }

    /** Volumen unitario en metros cúbicos. */
    public BigDecimal volumenM3() {
        return largoCm.divide(CENTIMETROS_POR_METRO)
                .multiply(anchoCm.divide(CENTIMETROS_POR_METRO))
                .multiply(altoCm.divide(CENTIMETROS_POR_METRO));
    }

    private static boolean esPositivo(BigDecimal valor) {
        return valor != null && valor.signum() > 0;
    }
}
