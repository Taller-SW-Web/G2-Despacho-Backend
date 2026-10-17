package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.ErrorEvidencia;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutorizarLecturaEvidenciaTests {
    private final LectorEvidencias lector = mock(LectorEvidencias.class);
    private final FirmadorEvidencia firmador = mock(FirmadorEvidencia.class);
    private final AutorizarLecturaEvidencia servicio = new AutorizarLecturaEvidencia(lector, firmador);

    @Test
    void permiteAlGestorSinPerfilLocalConsultarYRenovarEvidenciaHistorica() {
        when(lector.consultar("foto")).thenReturn(Optional.of(new LectorEvidencias.Referencia("foto", "despacho-historico", "salida-anterior/foto.png", "ASOCIADA")));
        when(lector.vigenciaSegundos()).thenReturn(120);
        when(firmador.firmar("salida-anterior/foto.png", 120)).thenReturn(new FirmadorEvidencia.AccesoObjeto("https://storage.example/primera", Instant.now().plusSeconds(120)),
                new FirmadorEvidencia.AccesoObjeto("https://storage.example/segunda", Instant.now().plusSeconds(120)));
        assertThat(servicio.autorizar("foto", "gestor-sin-perfil", true).idDespacho()).isEqualTo("despacho-historico");
        servicio.autorizar("foto", "gestor-sin-perfil", true);
        verify(lector, never()).tienePerfilActivo(anyString());
        verify(lector, never()).esPropietario(anyString(), anyString());
        verify(firmador, times(2)).firmar("salida-anterior/foto.png", 120);
    }
    @Test
    void rechazaPerfilInactivoYPropiedadAjenaAntesDeFirmar() {
        assertThatThrownBy(() -> servicio.autorizar("foto", "inactivo", false)).isInstanceOfSatisfying(ErrorEvidencia.class, error -> assertThat(error.estado()).isEqualTo(403));
        verify(lector, never()).consultar(anyString());
        when(lector.tienePerfilActivo("activo")).thenReturn(true);
        when(lector.consultar("foto")).thenReturn(Optional.of(new LectorEvidencias.Referencia("foto", "ajeno", "foto.png", "ASOCIADA")));
        assertThatThrownBy(() -> servicio.autorizar("foto", "activo", false)).isInstanceOfSatisfying(ErrorEvidencia.class, error -> assertThat(error.estado()).isEqualTo(403));
        verifyNoInteractions(firmador);
    }
    @Test
    void distingueReferenciaAusenteNoAsociadaYPoliticaInvalida() {
        when(lector.consultar("foto")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.autorizar("foto", "gestor", true)).isInstanceOfSatisfying(ErrorEvidencia.class, error -> assertThat(error.estado()).isEqualTo(404));
        when(lector.consultar("foto")).thenReturn(Optional.of(new LectorEvidencias.Referencia("foto", "despacho", "foto.png", "CARGADA")));
        assertThatThrownBy(() -> servicio.autorizar("foto", "gestor", true)).isInstanceOfSatisfying(ErrorEvidencia.class, error -> assertThat(error.estado()).isEqualTo(409));
        when(lector.consultar("foto")).thenReturn(Optional.of(new LectorEvidencias.Referencia("foto", "despacho", "foto.png", "ASOCIADA")));
        when(lector.vigenciaSegundos()).thenReturn(0);
        assertThatThrownBy(() -> servicio.autorizar("foto", "gestor", true)).isInstanceOfSatisfying(ErrorEvidencia.class, error -> assertThat(error.estado()).isEqualTo(503));
        verifyNoInteractions(firmador);
    }
}
