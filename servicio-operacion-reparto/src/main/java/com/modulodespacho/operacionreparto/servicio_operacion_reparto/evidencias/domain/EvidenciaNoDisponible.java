package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain;

public class EvidenciaNoDisponible extends RuntimeException {
    public EvidenciaNoDisponible(String mensaje) {
        super(mensaje);
    }

    public EvidenciaNoDisponible(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
