package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalculadoraCotizacionTests {

    @Test
    void calculaTotalesYCostoSegunElCasoCa01() {
        DatosFisicos pol = new DatosFisicos(new BigDecimal("0.25"), new BigDecimal("10"),
                new BigDecimal("10"), new BigDecimal("10"));
        DatosFisicos zap = new DatosFisicos(new BigDecimal("1.40"), new BigDecimal("20"),
                new BigDecimal("20"), new BigDecimal("40"));

        CalculadoraCotizacion.Totales totales = CalculadoraCotizacion.calcular(
                List.of(new CalculadoraCotizacion.ItemCalculo(pol, 5),
                        new CalculadoraCotizacion.ItemCalculo(zap, 1)),
                new BigDecimal("1.25"));

        assertThat(totales.pesoTotalKg()).isEqualByComparingTo("2.65");
        assertThat(totales.volumenTotalM3()).isEqualByComparingTo("0.021");
        assertThat(totales.pesoCotizableKg()).isEqualByComparingTo("2.67625");

        BigDecimal costo = CalculadoraCotizacion.costo(new BigDecimal("10"), new BigDecimal("2"),
                new BigDecimal("3"), totales.pesoCotizableKg());
        assertThat(costo).isEqualByComparingTo("10");
    }

    @Test
    void cobraRecargoCuandoElPesoCotizableSuperaElIncluido() {
        BigDecimal costo = CalculadoraCotizacion.costo(new BigDecimal("10"), new BigDecimal("2"),
                new BigDecimal("1"), new BigDecimal("4"));
        assertThat(costo).isEqualByComparingTo("16");
    }

    @Test
    void detectaDatosFisicosIncompletos() {
        DatosFisicos incompleto = new DatosFisicos(BigDecimal.ZERO, new BigDecimal("10"),
                new BigDecimal("10"), new BigDecimal("10"));
        assertThat(incompleto.completo()).isFalse();
    }
}
