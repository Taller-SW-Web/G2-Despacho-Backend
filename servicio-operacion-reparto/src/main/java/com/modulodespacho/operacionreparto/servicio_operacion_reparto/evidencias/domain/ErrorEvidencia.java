package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain;

public class ErrorEvidencia extends RuntimeException {
    private final int estado;
    private final String codigo;
    public ErrorEvidencia(int estado, String codigo, String mensaje) { super(mensaje); this.estado = estado; this.codigo = codigo; }
    public int estado() { return estado; }
    public String codigo() { return codigo; }
    public static ErrorEvidencia noDisponible() {
        return new ErrorEvidencia(503, "DESP_ERROR_EVIDENCIA_NO_DISPONIBLE", "No se pudo autorizar la lectura de evidencia. Reintenta en unos momentos");
    }
}
