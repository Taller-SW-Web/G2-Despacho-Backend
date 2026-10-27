package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosReasignacion.*;

/** Recuperación hacia adelante: HTTP fuera de transacción; jamás libera una reserva transferida ante fallo local. */
@Service
@Profile("!integracion-local")
public class ReasignarDespacho {
    private final AlmacenReasignaciones almacen;
    private final PuertoTransferencia capacidad;
    private final Clock reloj;
    public ReasignarDespacho(AlmacenReasignaciones almacen, PuertoTransferencia capacidad, Clock reloj) {
        this.almacen = almacen; this.capacidad = capacidad; this.reloj = reloj;
    }
    public Respuesta ejecutar(UUID despacho, UUID clave, int version, Solicitud solicitud, String actor) {
        return procesar(almacen.preparar(despacho, clave, version, solicitud, actor));
    }
    public Respuesta procesar(Intencion intencion) {
        var resultado = almacen.resultado(intencion.id());
        if (resultado.isPresent()) return resultado.get();
        var rechazo = almacen.rechazo(intencion.id());
        if (rechazo.isPresent()) throw ErrorDeNegocio.de(rechazo.get().estado(), rechazo.get().codigo(), rechazo.get().mensaje());
        if (!almacen.reclamar(intencion.id())) throw ErrorDeNegocio.de(409, "DESP_ERROR_REASIGNACION_EN_PROCESO",
                "La reasignación está en proceso. Recupera el resultado con la misma solicitud");
        Transferencia transferencia;
        try { transferencia = capacidad.transferir(intencion); }
        catch (ErrorDeNegocio error) {
            if (error.getEstado() == 400 || error.getEstado() == 409 || error.getEstado() == 422)
                almacen.rechazar(intencion.id(), new Rechazo(error.getEstado(), error.getCodigo(), error.getMessage()));
            else almacen.aplazar(intencion.id());
            throw error;
        } catch (RuntimeException error) { almacen.aplazar(intencion.id()); throw noDisponible(); }
        try { return almacen.confirmar(intencion, transferencia, reloj.instant()); }
        catch (RuntimeException error) {
            // Una confirmación pudo persistir aunque se perdiera su respuesta.
            var confirmada = almacen.resultado(intencion.id());
            if (confirmada.isPresent()) return confirmada.get();
            almacen.aplazar(intencion.id());
            throw noDisponible();
        }
    }
    public void recuperarPendientes() {
        for (var intencion : almacen.recuperables()) {
            try { procesar(intencion); }
            catch (RuntimeException error) { /* La intención y su cerco permanecen durables. */ }
        }
    }
    private static ErrorDeNegocio noDisponible() { return ErrorDeNegocio.de(503, "DESP_ERROR_REASIGNACION_NO_DISPONIBLE",
            "No se pudo confirmar la reasignación. Recupera la misma solicitud antes de continuar"); }
}
