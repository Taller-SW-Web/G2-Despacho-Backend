package com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!integracion-local")
@EnableScheduling
@ConditionalOnProperty(name = "despacho.corte-jornada.habilitado", havingValue = "true")
public class CorteJornadas {
    private static final Logger LOG = LoggerFactory.getLogger(CorteJornadas.class);
    private final GestorJornadas jornadas;
    private final OperacionCampo campo;
    private final Clock reloj;
    private final LocalTime horaCorte;

    public CorteJornadas(
            GestorJornadas jornadas,
            OperacionCampo campo,
            Clock reloj,
            @Value("${despacho.corte-jornada.hora}") String horaCorte) {
        this.jornadas = jornadas;
        this.campo = campo;
        this.reloj = reloj;
        this.horaCorte = LocalTime.parse(horaCorte);
    }

    @Scheduled(fixedDelayString = "${despacho.corte-jornada.intervalo-ms}")
    public void ejecutar() {
        ZonedDateTime ahora = reloj.instant().atZone(ZoneId.of("America/Bogota"));
        var hasta = ahora.toLocalDate();
        if (ahora.toLocalTime().isBefore(horaCorte)) hasta = hasta.minusDays(1);
        for (UUID id : jornadas.consultarJornadasParaCorte(hasta)) {
            try {
                campo.cortarJornada(id);
            } catch (ErrorJornada error) {
                LOG.warn("Corte pendiente para jornada {} ({})", id, error.codigo());
            } catch (RuntimeException error) {
                LOG.error("Error al ejecutar el corte de la jornada " + id, error);
            }
        }
    }
}
