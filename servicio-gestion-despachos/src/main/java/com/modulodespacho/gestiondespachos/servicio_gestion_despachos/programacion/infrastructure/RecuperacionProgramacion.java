package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.AsignarDespacho;

@Component
@Profile("!integracion-local")
@ConditionalOnProperty(name = "despacho.programacion.recuperacion-habilitada", havingValue = "true", matchIfMissing = true)
public class RecuperacionProgramacion {
    private static final Logger REGISTRO = LoggerFactory.getLogger(RecuperacionProgramacion.class);
    private final AsignarDespacho asignacion;
    private final EntregaEventosProgramacion eventos;
    public RecuperacionProgramacion(AsignarDespacho asignacion, EntregaEventosProgramacion eventos) { this.asignacion = asignacion; this.eventos = eventos; }
    @Scheduled(fixedDelayString = "${despacho.programacion.intervalo-recuperacion-ms:30000}", initialDelayString = "${despacho.programacion.intervalo-recuperacion-ms:30000}")
    public void recuperar() {
        try { asignacion.recuperarPendientes(); eventos.ejecutar(); }
        catch (RuntimeException error) { REGISTRO.warn("No se pudo completar el ciclo de recuperación de programación"); }
    }
}
