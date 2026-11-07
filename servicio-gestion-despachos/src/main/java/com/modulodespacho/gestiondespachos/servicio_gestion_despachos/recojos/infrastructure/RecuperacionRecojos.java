package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.application.GestorRecojos;

/** Completa o compensa las asignaciones que quedaron a medias; los eventos salen por la recuperación de programación. */
@Component
@Profile("!integracion-local")
@ConditionalOnProperty(name = "despacho.programacion.recuperacion-habilitada", havingValue = "true", matchIfMissing = true)
public class RecuperacionRecojos {
    private static final Logger REGISTRO = LoggerFactory.getLogger(RecuperacionRecojos.class);
    private final GestorRecojos recojos;
    public RecuperacionRecojos(GestorRecojos recojos) { this.recojos = recojos; }
    @Scheduled(fixedDelayString = "${despacho.programacion.intervalo-recuperacion-ms:30000}", initialDelayString = "${despacho.programacion.intervalo-recuperacion-ms:30000}")
    public void recuperar() {
        try { recojos.recuperarPendientes(); }
        catch (RuntimeException error) { REGISTRO.warn("No se pudo completar el ciclo de recuperación de recojos"); }
    }
}
