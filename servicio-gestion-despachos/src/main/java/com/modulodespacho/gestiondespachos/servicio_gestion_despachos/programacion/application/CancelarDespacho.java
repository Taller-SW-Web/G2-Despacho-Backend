package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosCancelacion.*;

/** No mantiene transacciones durante HTTP ni recrea una reserva liberada ante un fallo local. */
@Service
@Profile("!integracion-local")
public class CancelarDespacho {
    private final AlmacenCancelaciones almacen;
    private final PuertoLiberacionCancelacion capacidad;
    private final Clock reloj;
    public CancelarDespacho(AlmacenCancelaciones almacen, PuertoLiberacionCancelacion capacidad, Clock reloj) {
        this.almacen = almacen; this.capacidad = capacidad; this.reloj = reloj;
    }
    public Resultado ejecutar(String pedido, UUID clave, Solicitud solicitud, String actor) {
        return procesar(almacen.preparar(pedido, clave, solicitud, actor, reloj.instant()));
    }
    public Resultado procesar(Intencion intencion) {
        var previa = almacen.resultado(intencion.id());
        if (previa.isPresent()) return previa.get();
        if (!almacen.reclamar(intencion.id())) throw ErrorDeNegocio.de(409, "DESP_ERROR_CANCELACION_EN_PROCESO",
                "La cancelación está en proceso; recupera la misma solicitud");
        try {
            var liberacion = capacidad.liberarPorCancelacion(intencion);
            return almacen.confirmar(intencion, liberacion, reloj.instant());
        } catch (RuntimeException error) {
            // La confirmación local pudo persistir aunque su respuesta se perdiera.
            var confirmada = almacen.resultado(intencion.id());
            if (confirmada.isPresent()) return confirmada.get();
            almacen.aplazar(intencion.id());
            throw ErrorDeNegocio.de(503, "DESP_ERROR_CANCELACION_NO_DISPONIBLE",
                    "No se pudo confirmar la cancelación. Reintenta con la misma clave y contenido");
        }
    }
    public void recuperarPendientes() {
        for (var intencion : almacen.recuperables()) {
            try { procesar(intencion); }
            catch (RuntimeException error) { /* La intención y su cerco permanecen disponibles para recuperación. */ }
        }
    }
}
