package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.JornadaBloqueante;
import java.util.List;

public class ErrorJornada extends RuntimeException {
    private final int estado;
    private final String codigo;
    private List<String> campos = List.of();
    private List<JornadaBloqueante> jornadasBloqueantes = List.of();

    public ErrorJornada(int estado, String codigo, String mensaje) {
        super(mensaje);
        this.estado = estado;
        this.codigo = codigo;
    }

    public int estado() {
        return estado;
    }

    public String codigo() {
        return codigo;
    }

    public List<String> campos() {
        return campos;
    }

    public ErrorJornada conCampos(List<String> campos) {
        this.campos = List.copyOf(campos);
        return this;
    }

    public static ErrorJornada conflicto(String mensaje) {
        return new ErrorJornada(409, "DESP_ERROR_CONFLICTO_JORNADA", mensaje);
    }

    public static ErrorJornada aperturaBloqueada(List<JornadaBloqueante> jornadas) {
        var error = conflicto("Existe otra jornada activa o carga pendiente de los recursos.");
        error.jornadasBloqueantes = List.copyOf(jornadas);
        return error;
    }

    public List<JornadaBloqueante> jornadasBloqueantes() {
        return jornadasBloqueantes;
    }

    public static ErrorJornada noApto(String mensaje) {
        return new ErrorJornada(422, "DESP_ERROR_RECURSO_NO_APTO", mensaje);
    }

    public static ErrorJornada noDisponible() {
        return new ErrorJornada(
                503,
                "DESP_ERROR_OPERACION_NO_DISPONIBLE",
                "No fue posible confirmar la operación de jornada o capacidad.");
    }
}
