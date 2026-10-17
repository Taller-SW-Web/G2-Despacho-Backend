package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.*;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.DetalleIncidencia.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Puertos simulados; no inicia Spring, PostgreSQL ni Docker. */
class ConsultarDetalleIncidenciaTests {
    @Test
    void calculaTodasLasRestriccionesConMaximoVariableSinBloquearLaRecepcion() {
        for (int intentos : new int[] {0, 2, 3, 4}) {
            for (boolean recibido : new boolean[] {false, true}) {
                for (boolean anulado : new boolean[] {false, true}) {
                    var acciones = AccionesIncidencia.calcular("FALLIDO", recibido, anulado, intentos, 3);
                    assertThat(acciones.confirmarRecepcion().habilitada()).isEqualTo(!recibido);
                    assertThat(acciones.cerrarComoDevueltoAOrigen().habilitada()).isEqualTo(recibido);
                    assertThat(acciones.reprogramar().habilitada()).isEqualTo(recibido && !anulado && intentos < 3);
                }
            }
        }
        assertThat(AccionesIncidencia.calcular("FALLIDO", false, true, 3, 3).reprogramar().motivosBloqueo())
                .containsExactly("RECEPCION_PENDIENTE", "PEDIDO_ANULADO", "MAXIMO_INTENTOS_ALCANZADO");
        assertThat(AccionesIncidencia.calcular("PENDIENTE_ASIGNACION", true, false, 1, 3).reprogramar().motivosBloqueo())
                .containsExactly("ESTADO_NO_FALLIDO");
    }

    @Test
    void conservaLaIncidenciaLocalYSuContadorCuandoOperacionFallaSinMutarNiHeredarDatos() {
        var locales = mock(LectorDetalleIncidencia.class);
        var operacion = mock(LectorDatosOperativos.class);
        Instant ahora = Instant.parse("2026-10-17T01:00:00Z");
        var incidencia = new Incidencia("incidencia-nueva", ahora, new Motivo("NO_INTENTADO", "No intentado"), null,
                false, new DatosOperativos.Repartidor("repartidor-nuevo", null),
                DatosOperativos.Jornada.desconocida("jornada-nueva", null), null,
                new Recepcion("PENDIENTE_RETORNO", null, null, null, null, null), new Evidencia(null, "SIN_EVIDENCIA"));
        var local = new DetalleIncidencia("despacho", "DSP-1", "FALLIDO", 4, ahora, ahora, null, 1, 3, false,
                incidencia, List.of(), List.of(), AccionesIncidencia.calcular("FALLIDO", false, false, 1, 3));
        when(locales.consultar("despacho")).thenReturn(local);
        when(operacion.consultar(List.of("repartidor-nuevo"), List.of("jornada-nueva")))
                .thenThrow(new IllegalStateException("Proveedor no disponible"));
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            var consulta = new ConsultarDetalleIncidencia(locales, operacion, Clock.fixed(ahora, ZoneOffset.UTC), hilos);
            var detalle = consulta.consultar("despacho");
            assertThat(detalle.consultadoEn()).isEqualTo(ahora);
            assertThat(detalle.numeroIntentos()).isEqualTo(1);
            assertThat(detalle.incidencia().idIncidencia()).isEqualTo("incidencia-nueva");
            assertThat(detalle.incidencia().evidencia()).isEqualTo(incidencia.evidencia());
            assertThat(detalle.incidencia().retornoAtrasado()).isNull();
            assertThat(detalle.incidencia().jornada().estado()).isEqualTo(DatosOperativos.EstadoJornada.DESCONOCIDA);
            assertThat(detalle.acciones().confirmarRecepcion().habilitada()).isTrue();
        }
        verify(locales).consultar("despacho"); verifyNoMoreInteractions(locales);
    }
}
