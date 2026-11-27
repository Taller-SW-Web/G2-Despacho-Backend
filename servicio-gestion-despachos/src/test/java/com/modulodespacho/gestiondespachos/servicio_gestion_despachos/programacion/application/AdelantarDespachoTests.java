package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import static org.mockito.Mockito.*;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdelantarDespachoTests {
    @Test
    void calculaHoyEnLimaDesdeUnSoloInstanteAunqueUtcSeaElDiaSiguiente() {
        var almacen = mock(AlmacenAdelantos.class);
        var ahora = Instant.parse("2026-11-25T02:00:00Z");
        var reloj = Clock.fixed(ahora, ZoneId.of("Asia/Tokyo"));
        UUID id = UUID.randomUUID();
        new AdelantarDespacho(almacen, reloj)
                .ejecutar(
                        id, "clave", 4, new ModelosAdelanto.Solicitud("Disponibilidad"), "gestor");
        verify(almacen)
                .adelantar(
                        id,
                        "clave",
                        4,
                        "Disponibilidad",
                        "gestor",
                        LocalDate.of(2026, 11, 24),
                        ahora);
        verifyNoMoreInteractions(almacen);
    }
}
