package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain;

import java.math.BigDecimal;

/** Datos físicos vigentes de un producto, provistos por Productos y Ofertas. */
public record DatosFisicos(BigDecimal pesoKg, BigDecimal largoCm, BigDecimal anchoCm, BigDecimal altoCm) {

    private static final BigDecimal CENTIMETROS_POR_METRO = new BigDecimal("100");

    public boolean completo() {
        return esPositivo(pesoKg) && esPositivo(largoCm) && esPositivo(anchoCm) && esPositivo(altoCm);
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
