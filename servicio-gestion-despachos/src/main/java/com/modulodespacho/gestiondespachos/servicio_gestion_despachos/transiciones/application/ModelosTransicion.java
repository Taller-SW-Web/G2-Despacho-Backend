package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.transiciones.application;

import java.time.Instant;

/** Contrato interno de Gestión §6.1: Operación solicita y Gestión decide sobre el estado canónico. */
public final class ModelosTransicion {
    private ModelosTransicion() {}

    public record SolicitudTransicion(String estadoDestino, String idRepartidor, String idJornada, String idEvidencia,
            String recibidoPor, String motivo, String comentario, String ejecutor, String origen) {}
    /** Marcas temporales del ciclo vigente del despacho, tomadas del historial. */
    public record Hitos(Instant asignadoEn, Instant enCaminoEn, Instant entregadoEn, Instant fallidoEn) {}
    public record RespuestaTransicion(String idDespacho, String estadoAnterior, String estado, int version,
            int numeroIntentos, String motivoFallo, Hitos hitos, Instant registradoEn) {}
}
