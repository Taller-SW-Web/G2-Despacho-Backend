package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain;

public class SolicitudEvidenciaInvalida extends RuntimeException {
    public SolicitudEvidenciaInvalida(String mensaje) {
        super(mensaje);
    }
}
