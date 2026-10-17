package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.AutorizadorCargaStorage;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.AutorizadorEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.PoliticaCargaEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.DespachoNoDisponible;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.TipoEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.infrastructure.RepositorioEvidenciaJdbc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutorizadorEvidenciaTests {
    private static final UUID ID_DESPACHO = UUID.fromString("b211d92d-baa4-4cea-a81d-c27f9a7e0192");
    private static final UUID ID_USUARIO = UUID.fromString("14633fc2-0e78-46fb-a804-d8789549033d");
    private static final String URL_FIRMADA = "https://proyecto.supabase.co/storage/v1/object/upload/sign/bucket/object?token=firmado";

    @Test
    void registraLaReferenciaSoloDespuesDeAutorizarStorageYValidaPropiedad() {
        RepositorioEvidenciaJdbc repositorio = mock(RepositorioEvidenciaJdbc.class);
        AutorizadorCargaStorage storage = mock(AutorizadorCargaStorage.class);
        PoliticaCargaEvidencia politica = politica();
        Instant venceEn = Instant.now().plusSeconds(7200);
        when(repositorio.perteneceADespachadorEnRuta(ID_DESPACHO, ID_USUARIO)).thenReturn(true);
        when(storage.autorizar(any(UUID.class), any(UUID.class)))
                .thenReturn(new AutorizadorCargaStorage.AutorizacionStorage(URL_FIRMADA, venceEn));
        when(repositorio.registrarAutorizada(any(), eq(ID_DESPACHO), anyString(), any(), eq("image/jpeg"),
                eq(4096), eq(ID_USUARIO.toString()), eq(ID_USUARIO), eq(venceEn))).thenReturn(true);
        AutorizadorEvidencia autorizador = new AutorizadorEvidencia(politica, repositorio, storage);

        var resultado = autorizador.autorizar(
                new AutorizadorEvidencia.Solicitud(ID_DESPACHO, TipoEvidencia.ENTREGA, "image/jpeg", 4096),
                ID_USUARIO.toString());

        assertThat(resultado.urlCarga()).isEqualTo(URL_FIRMADA);
        assertThat(resultado.venceEn()).isEqualTo(venceEn);
        verify(repositorio).registrarAutorizada(any(), eq(ID_DESPACHO),
                eq("evidencias/" + ID_DESPACHO + "/" + resultado.idEvidencia()),
                eq(TipoEvidencia.ENTREGA), eq("image/jpeg"), eq(4096),
                eq(ID_USUARIO.toString()), eq(ID_USUARIO), eq(venceEn));
    }

    @Test
    void noAutorizaNiSolicitaUrlParaUnDespachoAjeno() {
        RepositorioEvidenciaJdbc repositorio = mock(RepositorioEvidenciaJdbc.class);
        AutorizadorCargaStorage storage = mock(AutorizadorCargaStorage.class);
        when(repositorio.perteneceADespachadorEnRuta(ID_DESPACHO, ID_USUARIO)).thenReturn(false);
        AutorizadorEvidencia autorizador = new AutorizadorEvidencia(politica(), repositorio, storage);

        assertThatThrownBy(() -> autorizador.autorizar(
                new AutorizadorEvidencia.Solicitud(ID_DESPACHO, TipoEvidencia.FALLO, "image/jpeg", 4096),
                ID_USUARIO.toString())).isInstanceOf(DespachoNoDisponible.class);

        verifyNoInteractions(storage);
        verify(repositorio, never()).registrarAutorizada(any(), any(), anyString(), any(), anyString(),
                anyInt(), anyString(), any(), any());
    }

    @Test
    void noDevuelveUnaUrlSiLaRevalidacionAlPersistirFalla() {
        RepositorioEvidenciaJdbc repositorio = mock(RepositorioEvidenciaJdbc.class);
        AutorizadorCargaStorage storage = mock(AutorizadorCargaStorage.class);
        Instant venceEn = Instant.now().plusSeconds(7200);
        when(repositorio.perteneceADespachadorEnRuta(ID_DESPACHO, ID_USUARIO)).thenReturn(true);
        when(storage.autorizar(any(UUID.class), any(UUID.class)))
                .thenReturn(new AutorizadorCargaStorage.AutorizacionStorage(URL_FIRMADA, venceEn));
        when(repositorio.registrarAutorizada(any(), eq(ID_DESPACHO), anyString(), any(), eq("image/jpeg"),
                eq(4096), eq(ID_USUARIO.toString()), eq(ID_USUARIO), eq(venceEn))).thenReturn(false);
        AutorizadorEvidencia autorizador = new AutorizadorEvidencia(politica(), repositorio, storage);

        assertThatThrownBy(() -> autorizador.autorizar(
                new AutorizadorEvidencia.Solicitud(ID_DESPACHO, TipoEvidencia.ENTREGA, "image/jpeg", 4096),
                ID_USUARIO.toString())).isInstanceOf(DespachoNoDisponible.class);
    }

    private PoliticaCargaEvidencia politica() {
        return new PoliticaCargaEvidencia("image/jpeg", 10_000, "https://proyecto.supabase.co",
                "secreto-de-prueba", "evidencias");
    }
}
