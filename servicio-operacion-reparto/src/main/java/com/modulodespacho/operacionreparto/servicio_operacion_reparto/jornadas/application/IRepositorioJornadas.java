package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;

public interface IRepositorioJornadas {
    void bloquearOperacion();
    List<FilaBase> consultarMonitoreo(LocalDate fecha);
    Perfil obtenerPerfil(UUID id);
    Optional<Perfil> buscarPerfilDeUsuario(UUID usuario);
    Vehiculo obtenerVehiculo(UUID id);
    JornadaBase obtenerJornada(UUID id);
    boolean tieneRecursoOcupado(UUID repartidor, UUID furgoneta);
    UUID crear(Apertura datos, Capacidad capacidad, UUID actor, Instant ahora);
    void cerrar(UUID jornada, String tipo, Instant ahora);
    void auditar(UUID jornada, String accion, UUID actor, String anterior);
    String fotografiar(UUID jornada);
    List<DespachoResumen> listarDespachos(UUID jornada);
    Optional<Reserva> buscarReservaPorClave(UUID clave);
    Reserva obtenerReserva(UUID id);
    boolean tieneReservaActiva(UUID despacho);
    void guardarReserva(UUID id, UUID clave, SolicitudReserva datos, String respuesta, String actor, Instant ahora);
    void liberar(UUID id, String motivo, String actor, Instant ahora);
    Optional<Long> buscarVersionProyeccion(UUID despacho);
    void guardarProyeccion(Proyeccion datos, String actor);
}
