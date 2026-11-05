package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Redondeo de resultados finales; los totales exactos se utilizan en la fórmula. */
public final class RedondeoCotizacion {
    private static final BigDecimal PASO_MONETARIO = new BigDecimal("0.50");

    private RedondeoCotizacion() {}

    public static BigDecimal costo(BigDecimal exacto) {
        return exacto.divide(PASO_MONETARIO, 0, RoundingMode.CEILING)
                .multiply(PASO_MONETARIO).setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal volumen(BigDecimal exacto) {
        return exacto.setScale(3, RoundingMode.CEILING);
    }
}
