package com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.application;

import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.ConflictoFlota;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.DatosFlota.*;

@Service
@Profile("!integracion-local")
@Transactional(timeout = 15)
public class GestorFlota {
    private final IRepositorioFlota repositorio;
    public GestorFlota(IRepositorioFlota repositorio) { this.repositorio = repositorio; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Pagina<Repartidor> listarRepartidores(int pagina, int tamano, EstadoRegistro registro, Vinculacion vinculacion, Turno turno) {
        return repositorio.listarRepartidores(pagina, tamano, registro, vinculacion, turno);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Pagina<Furgoneta> listarFurgonetas(int pagina, int tamano, EstadoFurgoneta estado, String placa) {
        return repositorio.listarFurgonetas(pagina, tamano, estado, placa == null ? null : placa.strip().toUpperCase(Locale.ROOT));
    }
    @Transactional(readOnly = true)
    public Repartidor obtenerRepartidor(UUID id) { return repositorio.obtenerRepartidor(id, false); }
    public Repartidor registrarRepartidor(AltaRepartidor datos, UUID actor) {
        Contacto contacto = normalizar(datos.contacto());
        Repartidor resultado = repositorio.registrarRepartidor(new AltaRepartidor(
                datos.nombres().strip(), datos.apellidos().strip(), datos.dni(), contacto), actor);
        repositorio.auditar("repartidores", resultado.idRepartidor(), "REGISTRO", null, actor);
        return resultado;
    }
    public Repartidor editarRepartidor(UUID id, Contacto datos, UUID actor) {
        repositorio.obtenerRepartidor(id, true);
        String anterior = repositorio.fotografiar("repartidores", id);
        Repartidor resultado = repositorio.editarRepartidor(id, normalizar(datos), actor);
        repositorio.auditar("repartidores", id, "EDICION", anterior, actor);
        return resultado;
    }
    public void darDeBaja(UUID id, UUID actor) {
        repositorio.bloquearOperacion();
        Repartidor actual = repositorio.obtenerRepartidor(id, true);
        if (actual.estadoRegistro() == EstadoRegistro.INACTIVO) return;
        repositorio.comprobarSinDespachos(id, true);
        String anterior = repositorio.fotografiar("repartidores", id);
        repositorio.cerrarJornadas(id, true, actor);
        repositorio.darDeBaja(id, actor);
        repositorio.auditar("repartidores", id, "BAJA", anterior, actor);
    }
    public void vincular(UUID id, UUID usuario, UUID actor) {
        Repartidor actual = repositorio.obtenerRepartidor(id, true);
        if (actual.estadoRegistro() != EstadoRegistro.ACTIVO)
            throw ConflictoFlota.conflicto("El repartidor está inactivo.");
        if (actual.usuarioId() != null) {
            if (actual.usuarioId().equals(usuario)) return;
            throw ConflictoFlota.conflicto("El repartidor ya tiene una cuenta vinculada.");
        }
        String anterior = repositorio.fotografiar("repartidores", id);
        repositorio.vincular(id, usuario, actor);
        repositorio.auditar("repartidores", id, "VINCULACION", anterior, actor);
    }
    public Furgoneta registrarFurgoneta(String placa, Capacidad datos, UUID actor) {
        Furgoneta resultado = repositorio.registrarFurgoneta(placa.strip().toUpperCase(Locale.ROOT), datos, actor);
        repositorio.auditar("furgonetas", resultado.idFurgoneta(), "REGISTRO", null, actor);
        return resultado;
    }
    public Furgoneta editarFurgoneta(UUID id, Capacidad datos, UUID actor) {
        repositorio.obtenerFurgoneta(id, true);
        String anterior = repositorio.fotografiar("furgonetas", id);
        Furgoneta resultado = repositorio.editarFurgoneta(id, datos, actor);
        repositorio.auditar("furgonetas", id, "EDICION", anterior, actor);
        return resultado;
    }
    public void cambiarEstado(UUID id, EstadoFurgoneta estado, UUID actor) {
        repositorio.bloquearOperacion();
        Furgoneta actual = repositorio.obtenerFurgoneta(id, true);
        if (actual.estado() == estado) return;
        if (actual.estado() == EstadoFurgoneta.FUERA_DE_SERVICIO && estado != EstadoFurgoneta.DISPONIBLE)
            throw ConflictoFlota.conflicto("La furgoneta debe rehabilitarse antes de pasar a mantenimiento.");
        if (estado != EstadoFurgoneta.DISPONIBLE) repositorio.comprobarSinDespachos(id, false);
        String anterior = repositorio.fotografiar("furgonetas", id);
        if (estado != EstadoFurgoneta.DISPONIBLE) repositorio.cerrarJornadas(id, false, actor);
        repositorio.cambiarEstado(id, estado, actor);
        repositorio.auditar("furgonetas", id, "CAMBIO_ESTADO", anterior, actor);
    }
    private Contacto normalizar(Contacto datos) {
        return new Contacto(datos.telefono().strip(), datos.correo().strip().toLowerCase(Locale.ROOT),
                datos.numeroBrevete().strip(), datos.turnoHabitual());
    }
}
