package com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.application;

import java.util.UUID;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.DatosFlota.*;

public interface IRepositorioFlota {
    Pagina<Repartidor> listarRepartidores(int pagina, int tamano, EstadoRegistro registro, Vinculacion vinculacion, Turno turno);
    Pagina<Furgoneta> listarFurgonetas(int pagina, int tamano, EstadoFurgoneta estado, String placa);
    Repartidor obtenerRepartidor(UUID id, boolean bloquear);
    Furgoneta obtenerFurgoneta(UUID id, boolean bloquear);
    Repartidor registrarRepartidor(AltaRepartidor datos, UUID actor);
    Repartidor editarRepartidor(UUID id, Contacto datos, UUID actor);
    Furgoneta registrarFurgoneta(String placa, Capacidad datos, UUID actor);
    Furgoneta editarFurgoneta(UUID id, Capacidad datos, UUID actor);
    void bloquearOperacion();
    void comprobarSinDespachos(UUID id, boolean repartidor);
    void cerrarJornadas(UUID id, boolean repartidor, UUID actor);
    void darDeBaja(UUID id, UUID actor);
    void vincular(UUID id, UUID usuario, UUID actor);
    void cambiarEstado(UUID id, EstadoFurgoneta estado, UUID actor);
    String fotografiar(String entidad, UUID id);
    void auditar(String entidad, UUID id, String accion, String anterior, UUID actor);
}
