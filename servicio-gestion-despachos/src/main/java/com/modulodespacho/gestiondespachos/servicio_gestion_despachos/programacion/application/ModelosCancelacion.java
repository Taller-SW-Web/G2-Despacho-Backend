package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.*;

public final class ModelosCancelacion {
    private ModelosCancelacion() {}
    public record Solicitud(@NotBlank @Size(max=128) String motivo, @Size(max=500) String observacion) {}
    public record Intencion(UUID id, UUID idDespacho, String idPedido, UUID clave, String ejecutor,
            int versionOrigen, String estadoOrigen, String idReserva, String idJornada, Solicitud solicitud) {}
    public record Respuesta(String idPedido, String idDespacho, String estado, Instant procesadoEn) {}
    public record Resultado(int codigoHttp, Respuesta respuesta) {}
    public record Liberacion(String idReserva, String idDespacho, String estado, String motivoLiberacion, Instant liberadaEn) {}
}
