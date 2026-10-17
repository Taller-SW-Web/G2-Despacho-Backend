package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias;

import org.junit.jupiter.api.Test;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.PoliticaCargaEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.EvidenciaNoDisponible;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.SolicitudEvidenciaInvalida;

import static org.assertj.core.api.Assertions.*;

class PoliticaCargaEvidenciaTests {
    private final PoliticaCargaEvidencia politica = new PoliticaCargaEvidencia(
            "image/jpeg, image/png", 2_000_000, "https://proyecto.supabase.co",
            "secreto-de-prueba", "evidencias");

    @Test
    void aceptaTipoYTamanoDentroDeLaPoliticaConfigurada() {
        assertThatCode(() -> politica.validar("IMAGE/JPEG", 2_000_000)).doesNotThrowAnyException();
    }

    @Test
    void rechazaTipoOTamanoFueraDeLaPoliticaConfigurada() {
        assertThatThrownBy(() -> politica.validar("image/gif", 100))
                .isInstanceOf(SolicitudEvidenciaInvalida.class);
        assertThatThrownBy(() -> politica.validar("image/jpeg", 2_000_001))
                .isInstanceOf(SolicitudEvidenciaInvalida.class);
        assertThatThrownBy(() -> politica.validar("image/jpeg", 0))
                .isInstanceOf(SolicitudEvidenciaInvalida.class);
    }

    @Test
    void noInventaUnaPoliticaNiCredencialesCuandoNoEstanConfiguradas() {
        PoliticaCargaEvidencia sinConfigurar = new PoliticaCargaEvidencia("", 0, "", "", "");

        assertThatThrownBy(() -> sinConfigurar.validar("image/jpeg", 100))
                .isInstanceOf(EvidenciaNoDisponible.class);
    }
}
