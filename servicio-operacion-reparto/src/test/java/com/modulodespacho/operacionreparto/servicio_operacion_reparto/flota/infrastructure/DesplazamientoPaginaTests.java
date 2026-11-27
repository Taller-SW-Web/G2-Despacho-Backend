package com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.infrastructure;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DesplazamientoPaginaTests {

    @Test
    void calculaElDesplazamientoDeCadaPagina() {
        assertThat(RepositorioFlotaJdbc.desplazamiento(1, 50)).isZero();
        assertThat(RepositorioFlotaJdbc.desplazamiento(3, 50)).isEqualTo(100L);
    }

    @Test
    void noSeDesbordaConLaPaginaMasAlta() {
        assertThat(RepositorioFlotaJdbc.desplazamiento(Integer.MAX_VALUE, 100))
                .isEqualTo((Integer.MAX_VALUE - 1L) * 100);
    }

    @Test
    void rechazaValoresFueraDeRango() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RepositorioFlotaJdbc.desplazamiento(0, 50));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RepositorioFlotaJdbc.desplazamiento(Integer.MIN_VALUE, 50));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RepositorioFlotaJdbc.desplazamiento(1, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RepositorioFlotaJdbc.desplazamiento(1, 101));
    }
}
