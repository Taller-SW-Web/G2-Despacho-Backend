package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

@Service
@Profile("!integracion-local")
public class GenerarDespachoPrueba {
    private final AlmacenProgramacion almacen;
    private final CoberturaProgramacion cobertura;
    private final Clock reloj;
    public GenerarDespachoPrueba(AlmacenProgramacion almacen, CoberturaProgramacion cobertura, Clock reloj) {
        this.almacen = almacen; this.cobertura = cobertura; this.reloj = reloj;
    }
    public ResultadoCreacion ejecutar(SolicitudSimulacion solicitud, String actor) {
        ZonaDisponible zona = cobertura.paraSimulacion(solicitud.idZona());
        long codigo = almacen.siguienteCodigo();
        BigDecimal peso = solicitud.pesoKg() != null ? solicitud.pesoKg() : BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(100, 2501), 2);
        BigDecimal volumen = solicitud.volumenM3() != null ? solicitud.volumenM3() : BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(5, 151), 3);
        var despacho = RecibirSolicitudDespacho.nuevo(codigo, true, zona.id(), peso, volumen, reloj.instant());
        despacho.setIdPedido("PED-SIM-" + codigo);
        despacho.setDestinatarioNombre("Cliente de prueba " + codigo);
        despacho.setDestinatarioTelefono("+51999999999");
        despacho.setDireccion("Dirección de prueba " + codigo + ", " + zona.distrito());
        despacho.setDistrito(zona.distrito());
        despacho.setCodigoPostal(zona.codigoPostal());
        despacho.setFechaProgramada(LocalDate.now(reloj.withZone(ZoneId.of("America/Lima"))));
        return almacen.crear(despacho, null, null, actor, solicitud.observaciones());
    }
}
