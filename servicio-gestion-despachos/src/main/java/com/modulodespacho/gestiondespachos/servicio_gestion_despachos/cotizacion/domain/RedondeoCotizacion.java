package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Redondeo de resultados finales; los totales exactos se utilizan en la fórmula. */
public final class RedondeoCotizacion {
    private static final BigDecimal PASO_MONETARIO = new BigDecimal("0.50");

    private RedondeoCotizacion() {}

    /**
     * Redondea el costo final hacia arriba a un múltiplo de S/ 0,50.
     * Por ejemplo, S/ 15,13 pasa a S/ 15,50 y S/ 15,50 se conserva.
     */
    public static BigDecimal costo(BigDecimal exacto) {
        return exacto.divide(PASO_MONETARIO, 0, RoundingMode.CEILING)
                .multiply(PASO_MONETARIO)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal volumen(BigDecimal exacto) {
        return exacto.setScale(3, RoundingMode.CEILING);
    }
}
