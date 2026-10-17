package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.Despacho;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

public interface AlmacenProgramacion {
    Optional<ResultadoCreacion> recuperarCreacion(String clave, String huella, String idPedido, String actor);
    ResultadoCreacion crear(Despacho despacho, String clave, String huella, String actor, String observacion);
    long siguienteCodigo();
    PaginaPendientes consultar(FiltrosPendientes filtros, LocalDate hoy, Instant ahora);
    IntencionAsignacion prepararAsignacion(UUID idDespacho, SolicitudAsignacion solicitud, String actor, LocalDate hoy);
    boolean reclamar(UUID id);
    RespuestaAsignacion confirmarAsignacion(IntencionAsignacion intencion, ReservaConfirmada reserva, Instant ahora);
    Optional<RespuestaAsignacion> resultadoAsignacion(UUID id);
    boolean prepararCompensacion(UUID id, String idReserva);
    void finalizarCompensacion(UUID id);
    void aplazar(UUID id);
    void rechazar(UUID id);
    List<IntencionAsignacion> recuperables();
}
