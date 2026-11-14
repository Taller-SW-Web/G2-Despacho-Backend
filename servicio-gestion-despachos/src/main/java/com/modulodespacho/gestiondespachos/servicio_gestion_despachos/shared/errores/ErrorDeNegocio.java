package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores;

import java.util.List;

/** Error de negocio traducible a una respuesta RFC 7807 con el codigo DESP_ERROR_*. */
public class ErrorDeNegocio extends RuntimeException {

    private final int estado;
    private final String codigo;
    private final List<ErrorDeCampo> errores;
    private String estadoActual;
    private Long versionDistrito;
    private List<String> skusAfectados = List.of();

    private ErrorDeNegocio(int estado, String codigo, String mensaje, List<ErrorDeCampo> errores) {
        super(mensaje);
        this.estado = estado;
        this.codigo = codigo;
        this.errores = errores;
    }

    public static ErrorDeNegocio de(int estado, String codigo, String mensaje) {
        return new ErrorDeNegocio(estado, codigo, mensaje, List.of());
    }

    public static ErrorDeNegocio de(
            int estado, String codigo, String mensaje, List<ErrorDeCampo> errores) {
        return new ErrorDeNegocio(estado, codigo, mensaje, errores);
    }

    public int getEstado() {
        return estado;
    }

    public String getCodigo() {
        return codigo;
    }

    public List<ErrorDeCampo> getErrores() {
        return errores;
    }

    public ErrorDeNegocio conEstadoActual(String estadoActual) {
        this.estadoActual = estadoActual;
        return this;
    }

    public String getEstadoActual() {
        return estadoActual;
    }

    public ErrorDeNegocio conVersionDistrito(Long version) {
        this.versionDistrito = version;
        return this;
    }

    public Long getVersionDistrito() {
        return versionDistrito;
    }

    public ErrorDeNegocio conSkusAfectados(List<String> skus) {
        this.skusAfectados = List.copyOf(skus);
        return this;
    }

    public List<String> getSkusAfectados() {
        return skusAfectados;
    }
}
