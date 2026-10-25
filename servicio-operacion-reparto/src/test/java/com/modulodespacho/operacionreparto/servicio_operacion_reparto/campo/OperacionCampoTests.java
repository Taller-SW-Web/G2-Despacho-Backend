package com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application.AlmacenCampo;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application.OperacionCampo;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application.PuertoGestion;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.domain.ModelosCampo.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.PoliticaCargaEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.VerificadorCargaStorage;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.VerificadorCargaStorage.ObjetoCargado;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Orquestación de F-03 con dobles: idempotencia, validaciones previas y tratamiento de errores de Gestión. */
class OperacionCampoTests {
    final GestorJornadas jornadas = mock(GestorJornadas.class);
    final AlmacenCampo almacen = mock(AlmacenCampo.class);
    final PuertoGestion gestion = mock(PuertoGestion.class);
    final VerificadorCargaStorage storage = mock(VerificadorCargaStorage.class);
    final PoliticaCargaEvidencia politica = new PoliticaCargaEvidencia("image/jpeg,image/png,image/webp", 2_097_152,
            "https://almacen.example.invalid", "clave", "evidencias");
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final OperacionCampo campo = new OperacionCampo(jornadas, almacen, gestion, storage, politica, json);
    final UUID usuario = UUID.randomUUID(), perfil = UUID.randomUUID(), jornada = UUID.randomUUID(), despacho = UUID.randomUUID();
    final UUID clave = UUID.randomUUID(), evidencia = UUID.randomUUID();
    final Instant ahora = Instant.parse("2026-10-17T15:41:00Z");

    @BeforeEach void preparar() {
        when(almacen.iniciar(eq(usuario), any(), anyString(), anyString())).thenReturn(new Comando(true, "EN_PROCESO", null));
        when(jornadas.consultarDespachoPropio(usuario, despacho)).thenReturn(detalle("EN_CAMINO"));
    }

    @Test void inicioDeTrasladoEnviaLaRutaPropiaYAplicaLaRespuestaDeGestion() {
        when(jornadas.despachoEnRuta(usuario, despacho)).thenReturn(enRuta("ASIGNADO"));
        when(gestion.transicionar(eq(despacho), eq(clave), any())).thenReturn(respuesta("EN_CAMINO", 0, null));

        assertThat(campo.iniciarTraslado(usuario, despacho, clave).estado()).isEqualTo("EN_CAMINO");

        var solicitud = ArgumentCaptor.forClass(SolicitudTransicion.class);
        verify(gestion).transicionar(eq(despacho), eq(clave), solicitud.capture());
        assertThat(solicitud.getValue()).isEqualTo(new SolicitudTransicion("EN_CAMINO", perfil.toString(), jornada.toString(),
                null, null, null, null, usuario.toString(), "MANUAL"));
        var proyeccion = ArgumentCaptor.forClass(Proyeccion.class);
        verify(jornadas).proyectar(eq(despacho), proyeccion.capture(), eq("GESTION_DESPACHOS"));
        assertThat(proyeccion.getValue().estado()).isEqualTo("EN_CAMINO");
        assertThat(proyeccion.getValue().version()).isEqualTo(4);
        assertThat(proyeccion.getValue().hitos().enCaminoEn()).isEqualTo(ahora);
        verify(almacen).completar(eq(usuario), eq(clave), anyString());
    }

    @Test void unaClaveCompletadaDevuelveLaRespuestaOriginalSinVolverAGestion() {
        when(almacen.iniciar(eq(usuario), eq(clave), eq("INICIO_TRASLADO"), anyString()))
                .thenReturn(new Comando(false, "COMPLETADO", json.writeValueAsString(detalle("EN_CAMINO"))));
        assertThat(campo.iniciarTraslado(usuario, despacho, clave).estado()).isEqualTo("EN_CAMINO");
        verifyNoInteractions(gestion);
        verify(jornadas, never()).despachoEnRuta(any(), any());
    }

    @Test void unReintentoEnProcesoNoRevalidaElEstadoLocalYaActualizado() {
        when(almacen.iniciar(eq(usuario), eq(clave), anyString(), anyString())).thenReturn(new Comando(false, "EN_PROCESO", null));
        when(jornadas.despachoEnRuta(usuario, despacho)).thenReturn(enRuta("EN_CAMINO"));
        when(gestion.transicionar(eq(despacho), eq(clave), any())).thenReturn(respuesta("EN_CAMINO", 0, null));
        assertThat(campo.iniciarTraslado(usuario, despacho, clave).estado()).isEqualTo("EN_CAMINO");
        verify(gestion).transicionar(eq(despacho), eq(clave), any());
    }

    @Test void unEstadoIncompatibleSeRechazaSinLlamarAGestionYLiberaLaClave() {
        when(jornadas.despachoEnRuta(usuario, despacho)).thenReturn(enRuta("ENTREGADO"));
        assertThatThrownBy(() -> campo.iniciarTraslado(usuario, despacho, clave))
                .isInstanceOfSatisfying(ErrorJornada.class, e -> assertThat(e.codigo()).isEqualTo("DESP_ERROR_ESTADO_INCOMPATIBLE"));
        verifyNoInteractions(gestion);
        verify(almacen).descartar(usuario, clave);
    }

    @Test void unaIndisponibilidadDeGestionConservaLaClaveParaReintentar() {
        when(jornadas.despachoEnRuta(usuario, despacho)).thenReturn(enRuta("ASIGNADO"));
        when(gestion.transicionar(any(), any(), any())).thenThrow(new ErrorJornada(503, "DESP_ERROR_GESTION_NO_DISPONIBLE", "Reintenta"));
        assertThatThrownBy(() -> campo.iniciarTraslado(usuario, despacho, clave)).isInstanceOf(ErrorJornada.class);
        verify(almacen, never()).descartar(any(), any());
        verify(almacen, never()).completar(any(), any(), any());
        verify(jornadas, never()).proyectar(any(), any(), any());
    }

    @Test void entregaVerificaLaFotoEnElBucketYLaAsociaTrasLaConfirmacion() {
        when(jornadas.despachoEnRuta(usuario, despacho)).thenReturn(enRuta("EN_CAMINO"));
        when(almacen.evidencia(evidencia, despacho, "ENTREGA", usuario.toString()))
                .thenReturn(new EvidenciaPendiente(evidencia, "evidencias/" + despacho + "/" + evidencia, "image/jpeg"));
        when(storage.verificar("evidencias/" + despacho + "/" + evidencia)).thenReturn(Optional.of(new ObjetoCargado(512_000, "image/jpeg")));
        when(gestion.transicionar(eq(despacho), eq(clave), any())).thenReturn(respuesta("ENTREGADO", 0, null));

        campo.registrarEntrega(usuario, despacho, clave, evidencia, "  Carlos Quispe ");

        var solicitud = ArgumentCaptor.forClass(SolicitudTransicion.class);
        verify(gestion).transicionar(eq(despacho), eq(clave), solicitud.capture());
        assertThat(solicitud.getValue().idEvidencia()).isEqualTo(evidencia.toString());
        assertThat(solicitud.getValue().recibidoPor()).isEqualTo("Carlos Quispe");
        var orden = inOrder(almacen, gestion);
        orden.verify(almacen).marcarCargada(evidencia);
        orden.verify(gestion).transicionar(any(), any(), any());
        orden.verify(almacen).asociar(evidencia);
    }

    @Test void unaFotoAunNoSubidaNoLlegaAGestion() {
        when(jornadas.despachoEnRuta(usuario, despacho)).thenReturn(enRuta("EN_CAMINO"));
        when(almacen.evidencia(any(), any(), any(), any())).thenReturn(new EvidenciaPendiente(evidencia, "evidencias/x/y", "image/jpeg"));
        when(storage.verificar(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> campo.registrarEntrega(usuario, despacho, clave, evidencia, null))
                .isInstanceOfSatisfying(ErrorJornada.class, e -> assertThat(e.codigo()).isEqualTo("DESP_ERROR_EVIDENCIA_NO_CARGADA"));
        verifyNoInteractions(gestion);
    }

    @Test void unaFotoQueExcedeLaPoliticaOCambiaDeFormatoSeRechaza() {
        when(jornadas.despachoEnRuta(usuario, despacho)).thenReturn(enRuta("EN_CAMINO"));
        when(almacen.evidencia(any(), any(), any(), any())).thenReturn(new EvidenciaPendiente(evidencia, "evidencias/x/y", "image/jpeg"));
        when(storage.verificar(anyString())).thenReturn(Optional.of(new ObjetoCargado(3_000_000, "image/jpeg")));
        assertThatThrownBy(() -> campo.registrarFallo(usuario, despacho, clave, "CLIENTE_AUSENTE", evidencia, null))
                .isInstanceOfSatisfying(ErrorJornada.class, e -> assertThat(e.codigo()).isEqualTo("DESP_ERROR_EVIDENCIA_INVALIDA"));
        when(storage.verificar(anyString())).thenReturn(Optional.of(new ObjetoCargado(1000, "image/png")));
        assertThatThrownBy(() -> campo.registrarFallo(usuario, despacho, UUID.randomUUID(), "CLIENTE_AUSENTE", evidencia, null))
                .isInstanceOfSatisfying(ErrorJornada.class, e -> assertThat(e.codigo()).isEqualTo("DESP_ERROR_EVIDENCIA_INVALIDA"));
        verifyNoInteractions(gestion);
    }

    @Test void noIntentadoNoPuedeRegistrarseDesdeElFormularioDeFallo() {
        assertThatThrownBy(() -> campo.registrarFallo(usuario, despacho, clave, "NO_INTENTADO", evidencia, null))
                .isInstanceOfSatisfying(ErrorJornada.class, e -> assertThat(e.codigo()).isEqualTo("DESP_ERROR_MOTIVO_INVALIDO"));
        verifyNoInteractions(gestion);
    }

    @Test void elCierreResuelvePendientesComoNoIntentadoConClavesDerivadasEstables() {
        UUID otro = UUID.randomUUID();
        when(jornadas.pendientesPropios(usuario, jornada)).thenReturn(List.of(enRuta("ASIGNADO"), enRuta(otro, "EN_CAMINO")));
        when(gestion.transicionar(any(), any(), any())).thenAnswer(llamada ->
                new RespuestaTransicion(llamada.getArgument(0).toString(), "ASIGNADO", "FALLIDO", 4, 0, "NO_INTENTADO",
                        new Hitos(ahora, null, null, ahora), ahora));
        when(jornadas.cerrarPropia(usuario, jornada)).thenReturn(null);

        campo.cerrarJornada(usuario, jornada, clave);
        campo.cerrarJornada(usuario, jornada, clave);

        var claves = ArgumentCaptor.forClass(UUID.class);
        var solicitudes = ArgumentCaptor.forClass(SolicitudTransicion.class);
        verify(gestion, times(4)).transicionar(any(), claves.capture(), solicitudes.capture());
        assertThat(claves.getAllValues()).doesNotContain(clave).hasSize(4);
        assertThat(claves.getAllValues().subList(0, 2)).isEqualTo(claves.getAllValues().subList(2, 4));
        assertThat(solicitudes.getAllValues()).allSatisfy(s -> {
            assertThat(s.estadoDestino()).isEqualTo("FALLIDO");
            assertThat(s.motivo()).isEqualTo("NO_INTENTADO");
            assertThat(s.idEvidencia()).isNull();
        });
        verify(jornadas, times(2)).cerrarPropia(usuario, jornada);
    }

    @Test void losMotivosDeCampoExcluyenInactivosYNoSeleccionables() {
        when(gestion.motivos()).thenReturn(List.of(new Motivo("CLIENTE_AUSENTE", "Cliente ausente", true, true, true),
                new Motivo("NO_INTENTADO", "No intentado", false, false, true), new Motivo("ANTIGUO", "Antiguo", true, true, false)));
        assertThat(campo.motivosDeCampo()).extracting(Motivo::codigo).containsExactly("CLIENTE_AUSENTE");
    }

    private DespachoEnRuta enRuta(String estado) { return enRuta(despacho, estado); }
    private DespachoEnRuta enRuta(UUID id, String estado) {
        return new DespachoEnRuta(perfil, jornada, new Proyeccion(id, "PED-1", "TRK-1", estado, jornada, UUID.randomUUID(), 1,
                new Destinatario("Carlos Quispe", "+51987654321"), new Destino("Jr. Pumacahua 1180", null, null, null, "Jesús María", null, null),
                LocalDate.of(2026, 10, 17), 0, 3, ahora, 2, null, new Hitos(ahora.minusSeconds(9000), null, null, null)));
    }
    private RespuestaTransicion respuesta(String estado, int intentos, String motivo) {
        return new RespuestaTransicion(despacho.toString(), "ASIGNADO", estado, 4, intentos, motivo,
                new Hitos(ahora.minusSeconds(9000), ahora, "ENTREGADO".equals(estado) ? ahora : null, null), ahora);
    }
    private DetalleDespacho detalle(String estado) {
        return new DetalleDespacho(jornada, despacho, "TRK-1", estado, 1, LocalDate.of(2026, 10, 17), 0, 2, null, null, null,
                new Hitos(ahora.minusSeconds(9000), ahora, null, null));
    }
}
