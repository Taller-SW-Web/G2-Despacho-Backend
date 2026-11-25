package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!integracion-local")
public class AdelantarDespacho {
    private final AlmacenAdelantos almacen;
    private final Clock reloj;

    public AdelantarDespacho(AlmacenAdelantos almacen, Clock reloj) {
        this.almacen = almacen;
        this.reloj = reloj;
    }

    public ModelosAdelanto.Respuesta ejecutar(
            UUID id, String clave, int version, ModelosAdelanto.Solicitud solicitud, String actor) {
        var ahora = reloj.instant();
        return almacen.adelantar(
                id,
                clave,
                version,
                solicitud.motivo(),
                actor,
                LocalDate.ofInstant(ahora, ZoneId.of("America/Lima")),
                ahora);
    }
}
