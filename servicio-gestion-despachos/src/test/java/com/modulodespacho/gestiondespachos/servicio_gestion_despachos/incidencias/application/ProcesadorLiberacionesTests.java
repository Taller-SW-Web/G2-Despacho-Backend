package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ProcesadorLiberacionesTests {
    private final LiberacionesPendientes pendientes = mock(LiberacionesPendientes.class);
    private final LiberadorCapacidad operacion = mock(LiberadorCapacidad.class);
    private final ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor();
    private final Instant ahora = Instant.parse("2026-10-24T15:00:00Z");
    private final ProcesadorLiberaciones procesador = new ProcesadorLiberaciones(pendientes, operacion,
            Clock.fixed(ahora, ZoneOffset.UTC), hilos, List.of(Duration.ofSeconds(30), Duration.ofMinutes(2),
                    Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1)));
    @AfterEach void cerrar() { hilos.close(); }
    @Test void usaCalendarioCompletoYLimitaErroresDeRevision() {
        long[] segundos = {30, 120, 600, 1800, 3600, 3600};
        for (int i = 1; i <= segundos.length; i++) {
            var envio = envio(i, false);
            when(pendientes.reclamar(ahora)).thenReturn(Optional.of(envio));
            when(operacion.liberar(envio)).thenReturn(LiberadorCapacidad.Resultado.fallo(false, "HTTP_503"));
            assertThat(procesador.procesarUna()).isTrue();
            verify(pendientes).reintentar(envio, ahora, ahora.plusSeconds(segundos[i - 1]), false, "HTTP_503");
        }
        var envio = envio(1, true);
        when(pendientes.reclamar(ahora)).thenReturn(Optional.of(envio));
        when(operacion.liberar(envio)).thenReturn(LiberadorCapacidad.Resultado.fallo(false, "HTTP_503"));
        procesador.procesarUna();
        verify(pendientes).reintentar(envio, ahora, ahora.plusSeconds(3600), true, "HTTP_503");
    }
    @Test void confirmaSinModificarRecepcionNiVersionYNoLlamaSinSolicitud() {
        when(pendientes.reclamar(ahora)).thenReturn(Optional.empty());
        assertThat(procesador.procesarUna()).isFalse();
        verifyNoInteractions(operacion);
        var envio = envio(3, true);
        when(pendientes.reclamar(ahora)).thenReturn(Optional.of(envio));
        when(operacion.liberar(envio)).thenReturn(new LiberadorCapacidad.Resultado(true, false, null, ahora.minusSeconds(80)));
        procesador.procesarUna();
        verify(pendientes).confirmar(envio, ahora, ahora.minusSeconds(80));
        verify(pendientes, never()).reintentar(any(), any(), any(), anyBoolean(), any());
    }
    @Test void caidaDeEscrituraConservaReclamacionParaRecuperacion() {
        var envio = envio(1, false);
        when(pendientes.reclamar(ahora)).thenReturn(Optional.of(envio));
        when(operacion.liberar(envio)).thenReturn(new LiberadorCapacidad.Resultado(true, false, null, ahora));
        doThrow(new IllegalStateException("fallo local simulado")).when(pendientes).confirmar(envio, ahora, ahora);
        assertThatCode(procesador::ejecutar).doesNotThrowAnyException();
        verify(pendientes, never()).reintentar(any(), any(), any(), anyBoolean(), any());
    }
    @Test void falloNoPrevistoEnPuertoSeReintenta() {
        var envio = envio(1, false);
        when(pendientes.reclamar(ahora)).thenReturn(Optional.of(envio));
        when(operacion.liberar(envio)).thenThrow(new IllegalStateException("fallo simulado"));
        procesador.procesarUna();
        verify(pendientes).reintentar(envio, ahora, ahora.plusSeconds(30), false, "OPERACION_NO_DISPONIBLE");
    }
    private LiberacionesPendientes.Envio envio(int intento, boolean revision) {
        return new LiberacionesPendientes.Envio(UUID.randomUUID(), UUID.randomUUID(), "despacho", "incidencia",
                "reserva-original", "jornada-original", intento, revision, true);
    }
}
