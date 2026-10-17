package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application;

public class CotizacionInvalidaException extends RuntimeException {

    private final String codigo;
    private final int estado;

    public CotizacionInvalidaException(String codigo, String mensaje, int estado) {
        super(mensaje);
        this.codigo = codigo;
        this.estado = estado;
    }

    public String getCodigo() {
        return codigo;
    }

    public int getEstado() {
        return estado;
    }
}
