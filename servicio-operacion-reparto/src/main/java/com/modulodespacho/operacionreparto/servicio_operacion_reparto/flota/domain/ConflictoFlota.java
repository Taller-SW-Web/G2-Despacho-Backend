package com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain;

public class ConflictoFlota extends RuntimeException {
    private final int estado;
    private final String codigo;
    public ConflictoFlota(int estado, String codigo, String mensaje) {
        super(mensaje);
        this.estado = estado;
        this.codigo = codigo;
    }
    public int estado() { return estado; }
    public String codigo() { return codigo; }
    public static ConflictoFlota conflicto(String mensaje) {
        return new ConflictoFlota(409, "DESP_ERROR_CONFLICTO_FLOTA", mensaje);
    }
    public static ConflictoFlota inexistente() {
        return new ConflictoFlota(404, "DESP_ERROR_RECURSO_NO_ENCONTRADO", "El recurso no existe.");
    }
}
