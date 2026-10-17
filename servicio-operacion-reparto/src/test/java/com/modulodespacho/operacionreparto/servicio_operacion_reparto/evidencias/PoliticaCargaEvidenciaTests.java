package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias;

import org.junit.jupiter.api.Test;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.PoliticaCargaEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.EvidenciaNoDisponible;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.SolicitudEvidenciaInvalida;

import static org.assertj.core.api.Assertions.*;

class PoliticaCargaEvidenciaTests {
    private final PoliticaCargaEvidencia politica = new PoliticaCargaEvidencia(
            "image/jpeg, image/png, image/webp", 2_097_152, "https://proyecto.supabase.co",
            "secreto-de-prueba", "evidencias");

    @Test
    void aceptaTipoYTamanoDentroDeLaPoliticaConfigurada() {
        assertThatCode(() -> politica.validar("IMAGE/JPEG", 2_097_152)).doesNotThrowAnyException();
        assertThatCode(() -> politica.validar("image/webp", 100)).doesNotThrowAnyException();
    }

    @Test
    void rechazaTipoOTamanoFueraDeLaPoliticaConfigurada() {
        assertThatThrownBy(() -> politica.validar("image/gif", 100))
                .isInstanceOf(SolicitudEvidenciaInvalida.class);
        assertThatThrownBy(() -> politica.validar("image/jpeg", 2_097_153))
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

    @Test
    void rechazaConfiguracionFueraDeRestriccionesDelEsquema() {
        PoliticaCargaEvidencia unsupportedType = new PoliticaCargaEvidencia(
                "image/gif", 100, "https://proyecto.supabase.co", "test-service-key", "evidencias");
        PoliticaCargaEvidencia unsupportedLimit = new PoliticaCargaEvidencia(
                "image/jpeg", 2_097_153, "https://proyecto.supabase.co", "test-service-key", "evidencias");

        assertThatThrownBy(() -> unsupportedType.validar("image/gif", 100))
                .isInstanceOf(EvidenciaNoDisponible.class);
        assertThatThrownBy(() -> unsupportedLimit.validar("image/jpeg", 100))
                .isInstanceOf(EvidenciaNoDisponible.class);
    }
}
