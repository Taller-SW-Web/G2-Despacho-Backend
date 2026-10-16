package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain;

import java.math.BigDecimal;
import java.util.List;

/** Cálculo puro de totales y costo según la fórmula de F-01. */
public final class CalculadoraCotizacion {

    private CalculadoraCotizacion() {
    }

    public record ItemCalculo(DatosFisicos datos, int cantidad) {
    }

    public record Totales(BigDecimal pesoTotalKg, BigDecimal volumenTotalM3, BigDecimal pesoCotizableKg) {
    }

    public static Totales calcular(List<ItemCalculo> items, BigDecimal factorVolumetrico) {
        BigDecimal pesoTotal = BigDecimal.ZERO;
        BigDecimal volumenTotal = BigDecimal.ZERO;
        for (ItemCalculo item : items) {
            BigDecimal cantidad = BigDecimal.valueOf(item.cantidad());
            pesoTotal = pesoTotal.add(item.datos().pesoKg().multiply(cantidad));
            volumenTotal = volumenTotal.add(item.datos().volumenM3().multiply(cantidad));
        }
        BigDecimal factor = factorVolumetrico == null ? BigDecimal.ZERO : factorVolumetrico;
        BigDecimal pesoCotizable = pesoTotal.add(volumenTotal.multiply(factor));
        return new Totales(pesoTotal, volumenTotal, pesoCotizable);
    }

    public static BigDecimal costo(BigDecimal tarifaBase, BigDecimal recargoKgAdicional,
            BigDecimal pesoIncluidoKg, BigDecimal pesoCotizableKg) {
        BigDecimal recargo = recargoKgAdicional == null ? BigDecimal.ZERO : recargoKgAdicional;
        BigDecimal incluido = pesoIncluidoKg == null ? BigDecimal.ZERO : pesoIncluidoKg;
        BigDecimal excedente = pesoCotizableKg.subtract(incluido).max(BigDecimal.ZERO);
        return tarifaBase.add(recargo.multiply(excedente));
    }
}
