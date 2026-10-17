package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

/** HTTP siempre fuera de las transacciones locales; intención y compensación recuperables. */
@Service
@Profile("!integracion-local")
public class AsignarDespacho {
    private final AlmacenProgramacion almacen;
    private final PuertoCapacidad capacidad;
    private final Clock reloj;
    public AsignarDespacho(AlmacenProgramacion almacen, PuertoCapacidad capacidad, Clock reloj) {
        this.almacen = almacen; this.capacidad = capacidad; this.reloj = reloj;
    }
    public RespuestaAsignacion ejecutar(UUID despacho, SolicitudAsignacion solicitud, String actor) {
        var intencion = almacen.prepararAsignacion(despacho, solicitud, actor,
                LocalDate.now(reloj.withZone(ZoneId.of("America/Lima"))));
        if (intencion.respuesta() != null) return intencion.respuesta();
        return procesar(intencion);
    }
    public RespuestaAsignacion procesar(IntencionAsignacion intencion) {
        if (!almacen.reclamar(intencion.id())) throw ErrorDeNegocio.de(409,
                "DESP_ERROR_ASIGNACION_EN_PROCESO", "La asignación se está procesando; vuelve a consultar");
        if ("COMPENSACION_PENDIENTE".equals(intencion.estado())) {
            compensar(intencion.id(), intencion.idReserva(), intencion.idDespacho());
            throw ErrorDeNegocio.de(409, "DESP_ERROR_ASIGNACION_COMPENSADA", "La asignación anterior no fue confirmada");
        }
        ReservaConfirmada reserva;
        try { reserva = capacidad.reservar(intencion); }
        catch (ErrorDeNegocio error) {
            if (error.getEstado() == 409 || error.getEstado() == 422) almacen.rechazar(intencion.id());
            else almacen.aplazar(intencion.id());
            throw error;
        } catch (RuntimeException error) {
            almacen.aplazar(intencion.id());
            throw ErrorDeNegocio.de(503, "DESP_ERROR_OPERACION_NO_DISPONIBLE", "No se pudo confirmar la reserva de capacidad");
        }
        try { return almacen.confirmarAsignacion(intencion, reserva, reloj.instant()); }
        catch (RuntimeException error) {
            // Antes de compensar comprobar una posible confirmación concurrente o respuesta perdida.
            var aceptada = almacen.resultadoAsignacion(intencion.id());
            if (aceptada.isPresent()) return aceptada.get();
            if (!almacen.prepararCompensacion(intencion.id(), reserva.idReserva())) {
                // Otra recuperación pudo confirmar después de la lectura anterior.
                return almacen.resultadoAsignacion(intencion.id()).orElseThrow(() -> ErrorDeNegocio.de(503,
                        "DESP_ERROR_ASIGNACION_NO_DISPONIBLE", "Consulta el resultado de la asignación antes de reintentar"));
            }
            compensar(intencion.id(), reserva.idReserva(), intencion.idDespacho());
            if (error instanceof ErrorDeNegocio negocio) throw negocio;
            if (error instanceof jakarta.persistence.OptimisticLockException || error instanceof org.springframework.dao.OptimisticLockingFailureException)
                throw ErrorDeNegocio.de(409, "DESP_ERROR_DESPACHO_DESACTUALIZADO", "El despacho cambió mientras se asignaba");
            throw ErrorDeNegocio.de(503, "DESP_ERROR_ASIGNACION_NO_DISPONIBLE", "No se pudo confirmar la asignación; consulta su resultado antes de reintentar");
        }
    }
    private void compensar(UUID operacion, String reserva, UUID despacho) {
        try {
            capacidad.compensar(reserva, despacho.toString());
            almacen.finalizarCompensacion(operacion);
        } catch (RuntimeException error) {
            almacen.aplazar(operacion);
        }
    }
    public void recuperarPendientes() {
        for (var intencion : almacen.recuperables()) {
            try { procesar(intencion); }
            catch (RuntimeException error) { /* La intención conserva estado y próximo intento durables. */ }
        }
    }
}
