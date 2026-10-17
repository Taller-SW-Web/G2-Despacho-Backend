package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain;

public class DespachoNoDisponible extends RuntimeException {
    public DespachoNoDisponible() {
        super("El despacho no está disponible para este repartidor.");
    }
}
