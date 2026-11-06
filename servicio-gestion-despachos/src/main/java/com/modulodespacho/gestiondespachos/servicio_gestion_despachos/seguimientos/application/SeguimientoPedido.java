package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.seguimientos.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.EstadoDespacho;

/** Proyección pública: no contiene datos personales, evidencias ni detalles operativos. */
public record SeguimientoPedido(String idPedido, EstadoDespacho estado, String estadoEtiqueta,
        LocalDate fechaProgramada, String distrito, List<Hito> hitos, Instant actualizadoEn) {

    public SeguimientoPedido {
        hitos = List.copyOf(hitos);
    }

    public record Hito(EstadoDespacho estado, String etiqueta, Instant fecha) {}

    public static String etiqueta(EstadoDespacho estado) {
        return switch (estado) {
            case PENDIENTE_ASIGNACION -> "En centro de despacho";
            case ASIGNADO -> "Asignado a repartidor";
            case EN_CAMINO -> "En camino";
            case FALLIDO -> "No entregado, regresando al centro";
            case ENTREGADO -> "Entregado";
            case DEVUELTO_A_ORIGEN -> "Devuelto a origen";
            case CANCELADO -> "Cancelado";
        };
    }
}
