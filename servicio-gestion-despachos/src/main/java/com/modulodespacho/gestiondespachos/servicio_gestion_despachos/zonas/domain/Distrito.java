package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "distrito")
public class Distrito extends EntidadConFechas {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "provincia")
    private String provincia;

    @Column(name = "departamento")
    private String departamento;

    @Column(name = "ubigeo")
    private String ubigeo;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private EstadoDistrito estado = EstadoDistrito.ACTIVO;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "catalogo_base", nullable = false, updatable = false)
    private boolean catalogoBase;

    public EstadoDistrito getEstado() {
        return estado;
    }

    public void setEstado(EstadoDistrito estado) {
        this.estado = estado;
    }

    public Long getVersion() {
        return version;
    }

    public boolean isCatalogoBase() {
        return catalogoBase;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getProvincia() {
        return provincia;
    }

    public void setProvincia(String provincia) {
        this.provincia = provincia;
    }

    public String getDepartamento() {
        return departamento;
    }

    public void setDepartamento(String departamento) {
        this.departamento = departamento;
    }

    public String getUbigeo() {
        return ubigeo;
    }

    public void setUbigeo(String ubigeo) {
        this.ubigeo = ubigeo;
    }
}
