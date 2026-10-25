package com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.domain;

import java.time.Instant;
import java.util.UUID;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.Hitos;

/** Comandos de F-03 y su intercambio con Gestión §6.1; Gestión decide el estado canónico. */
public final class ModelosCampo {
    private ModelosCampo() {}

    public record SolicitudTransicion(String estadoDestino, String idRepartidor, String idJornada, String idEvidencia,
            String recibidoPor, String motivo, String comentario, String ejecutor, String origen) {}
    public record RespuestaTransicion(String idDespacho, String estadoAnterior, String estado, int version,
            int numeroIntentos, String motivoFallo, Hitos hitos, Instant registradoEn) {}
    public record Motivo(String codigo, String etiqueta, boolean seleccionableEnCampo, boolean consumeIntento, boolean activo) {}
    /** Estado durable de una clave: nuevo indica que este intento debe validar antes de ir a Gestión. */
    public record Comando(boolean nuevo, String estado, String respuesta) {}
    public record EvidenciaPendiente(UUID id, String rutaObjeto, String tipoMime) {}
}
