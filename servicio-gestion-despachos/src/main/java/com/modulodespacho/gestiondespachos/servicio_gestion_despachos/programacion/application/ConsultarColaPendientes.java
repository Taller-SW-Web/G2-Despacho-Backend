package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

@Service
@Profile("!integracion-local")
public class ConsultarColaPendientes {
    private final AlmacenProgramacion almacen;
    private final Clock reloj;
    public ConsultarColaPendientes(AlmacenProgramacion almacen, Clock reloj) { this.almacen = almacen; this.reloj = reloj; }
    public PaginaPendientes ejecutar(FiltrosPendientes filtros) {
        var ahora = reloj.instant();
        return almacen.consultar(filtros, LocalDate.ofInstant(ahora, ZoneId.of("America/Lima")), ahora);
    }
}
