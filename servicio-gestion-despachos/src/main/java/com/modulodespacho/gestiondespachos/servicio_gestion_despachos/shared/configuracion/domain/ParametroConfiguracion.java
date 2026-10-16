package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.configuracion.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "parametros_configuracion")
public class ParametroConfiguracion {

    @Id
    @Column(name = "clave")
    private String clave;

    @Column(name = "valor", nullable = false)
    private String valor;

    @Column(name = "descripcion", nullable = false)
    private String descripcion;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    @Column(name = "actualizado_por", nullable = false)
    private String actualizadoPor;

    public String getClave() {
        return clave;
    }

    public String getValor() {
        return valor;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }

    public String getActualizadoPor() {
        return actualizadoPor;
    }
}
