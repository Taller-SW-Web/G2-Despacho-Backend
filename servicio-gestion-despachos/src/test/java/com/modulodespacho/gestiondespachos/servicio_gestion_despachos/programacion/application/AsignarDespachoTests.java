package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

class AsignarDespachoTests {
    @Test void noLiberaUnaReservaQueOtraRecuperacionAcabaDeConfirmar() {
        AlmacenProgramacion almacen = mock(AlmacenProgramacion.class);
        PuertoCapacidad capacidad = mock(PuertoCapacidad.class);
        Instant ahora = Instant.parse("2026-10-16T18:00:00Z");
        var intencion = new IntencionAsignacion(UUID.randomUUID(), UUID.randomUUID(), 1, "gestor", "REP-1", "JOR-1", null,
                "PENDIENTE", null, null, new Carga(BigDecimal.ONE, BigDecimal.ONE, 1));
        var reserva = new ReservaConfirmada("RES-1", "CONFIRMADA", new Carga(BigDecimal.TEN, BigDecimal.TEN, 10));
        var aceptada = new RespuestaAsignacion(intencion.idDespacho().toString(), "ASIGNADO", 2, "REP-1", "JOR-1", "RES-1", 1, null, ahora);
        when(almacen.reclamar(intencion.id())).thenReturn(true);
        when(capacidad.reservar(intencion)).thenReturn(reserva);
        when(almacen.confirmarAsignacion(intencion, reserva, ahora)).thenThrow(new DataAccessResourceFailureException("Fallo de prueba"));
        when(almacen.resultadoAsignacion(intencion.id())).thenReturn(Optional.empty(), Optional.of(aceptada));
        when(almacen.prepararCompensacion(intencion.id(), "RES-1")).thenReturn(false);
        assertThat(new AsignarDespacho(almacen, capacidad, Clock.fixed(ahora, ZoneOffset.UTC)).procesar(intencion)).isEqualTo(aceptada);
        verify(capacidad, never()).compensar(anyString(), anyString());
    }
}
