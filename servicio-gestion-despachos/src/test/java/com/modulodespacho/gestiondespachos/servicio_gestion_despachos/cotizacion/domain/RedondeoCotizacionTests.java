package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class RedondeoCotizacionTests {
    @ParameterizedTest
    @CsvSource({"0,0.00", "8,8.00", "8.12,8.50", "8.50,8.50", "8.78,9.00",
            "15.13,15.50", "15.51,16.00", "8.5001,9.00", "0.0001,0.50"})
    void elevaAlMedioSolSinRedondeoIntermedio(String exacto, String esperado) {
        assertThat(RedondeoCotizacion.costo(new BigDecimal(exacto))).isEqualTo(new BigDecimal(esperado));
    }

    @ParameterizedTest
    @CsvSource({"0.021112,0.022", "0.025822,0.026", "0.025135,0.026", "0.025,0.025",
            "0.21112,0.212", "0.000001,0.001", "0.02126,0.022"})
    void elevaElVolumenFinalAlSiguienteMilesimo(String exacto, String esperado) {
        assertThat(RedondeoCotizacion.volumen(new BigDecimal(exacto))).isEqualTo(new BigDecimal(esperado));
    }
}
