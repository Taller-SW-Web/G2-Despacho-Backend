package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.AccionesIncidencia;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Jornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Repartidor;

public record DetalleIncidencia(String idDespacho, String codigoRastreoInterno, String estado, int versionDespacho,
        Instant actualizadoEn, Instant ultimoCambioEstadoEn, Instant consultadoEn, int numeroIntentos,
        int maximoIntentos, boolean pedidoAnulado, Incidencia incidencia, List<EntradaHistorial> historial,
        List<Comunicacion> comunicaciones, AccionesIncidencia acciones) {
    public record Motivo(String codigo, String etiqueta) {}
    public record Incidencia(String idIncidencia, Instant fechaIncidencia, Motivo motivo, String comentario,
            boolean consumeIntento, Repartidor repartidor, Jornada jornada, Boolean retornoAtrasado,
            Recepcion recepcion, Evidencia evidencia) {}
    public record Recepcion(String estado, Instant confirmadaEn, String idUsuario, Boolean selloIntacto,
            String observacion, LiberacionCapacidad liberacionCapacidad) {}
    public record LiberacionCapacidad(String idSolicitud, String estado, boolean requiereRevision,
            Instant actualizadaEn, Instant confirmadaEn) {}
    public record Evidencia(String idEvidencia, String estadoReferencia) {}
    public record EntradaHistorial(String id, Instant fechaServidor, String tipoOperacion, String estadoAnterior,
            String estadoNuevo, String funcionalidadOrigen, String ejecutorTipo, String ejecutorId, String origen,
            Motivo motivo, String observacion, Map<String, Object> datos) {}
    public record Comunicacion(String idEvento, String tipoEvento, String estadoEnvio, Instant creadoEn, Instant enviadoEn) {}
}
