package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.Carga;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** La carga enviada a Operación respeta su precisión (3 decimales de peso, 4 de volumen). */
class CargaOperacionTests {

    @Test
    void redondeaHaciaArribaElVolumenYElPesoDeG6() {
        var carga =
                CapacidadProgramacionHttp.cargaOperacion(
                        new Carga(new BigDecimal("0.1855"), new BigDecimal("0.002208"), 1));

        assertThat(carga.pesoKg()).isEqualByComparingTo("0.186");
        assertThat(carga.pesoKg().scale()).isEqualTo(3);
        assertThat(carga.volumenM3()).isEqualByComparingTo("0.0023");
        assertThat(carga.volumenM3().scale()).isEqualTo(4);
        assertThat(carga.paquetes()).isEqualTo(1);
    }

    @Test
    void conservaLosValoresQueYaCumplenLaPrecision() {
        var carga =
                CapacidadProgramacionHttp.cargaOperacion(
                        new Carga(new BigDecimal("23.01"), new BigDecimal("0.075"), 2));

        assertThat(carga.pesoKg()).isEqualByComparingTo("23.01");
        assertThat(carga.volumenM3()).isEqualByComparingTo("0.075");
        assertThat(carga.paquetes()).isEqualTo(2);
    }

    @Test
    void elVolumenMinimoNuncaQuedaEnCero() {
        var carga =
                CapacidadProgramacionHttp.cargaOperacion(
                        new Carga(new BigDecimal("0.09"), new BigDecimal("0.000819"), 1));

        assertThat(carga.volumenM3()).isEqualByComparingTo("0.0009");
    }
}
