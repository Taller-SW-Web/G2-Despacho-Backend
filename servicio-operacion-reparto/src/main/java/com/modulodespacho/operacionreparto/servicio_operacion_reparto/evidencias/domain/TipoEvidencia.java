package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain;

public enum TipoEvidencia {
    ENTREGA,
    FALLO,
    RECOJO,
    FALLO_RECOJO;

    public boolean deRecojo() { return this == RECOJO || this == FALLO_RECOJO; }
}
