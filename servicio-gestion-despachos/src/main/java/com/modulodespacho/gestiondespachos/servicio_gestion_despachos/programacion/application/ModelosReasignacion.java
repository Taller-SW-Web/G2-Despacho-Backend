package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.Carga;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class ModelosReasignacion {
    private ModelosReasignacion() {}

    public record Solicitud(
            @NotBlank @Size(max = 128) String idNuevoRepartidor,
            @NotBlank @Size(max = 128) String idNuevaJornada,
            @NotBlank @Size(max = 500) String motivo) {}

    public record Intencion(
            UUID id,
            UUID idDespacho,
            UUID clave,
            String ejecutor,
            int versionOrigen,
            String idRepartidorOrigen,
            String idJornadaOrigen,
            String idReservaOrigen,
            Solicitud solicitud) {}

    public record Transferencia(
            String idReservaOrigen,
            String idDespacho,
            String idNuevaReserva,
            String idNuevaJornada,
            String estado,
            Carga capacidadRemanente,
            Instant transferidaEn) {}

    public record Respuesta(
            String idDespacho,
            String estado,
            int version,
            String idRepartidorAnterior,
            String idJornadaAnterior,
            String idRepartidor,
            String idJornada,
            String idReserva,
            int posicionSecuencia,
            String motivo,
            Instant actualizadoEn) {}

    public record Rechazo(int estado, String codigo, String mensaje) {}
}
