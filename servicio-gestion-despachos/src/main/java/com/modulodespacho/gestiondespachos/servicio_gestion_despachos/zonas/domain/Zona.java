package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "zonas")
public class Zona extends EntidadConFechas {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private EstadoZona estado = EstadoZona.ACTIVO;

    @Enumerated(EnumType.STRING)
    @Column(name = "origen_delimitacion")
    private OrigenDelimitacion origenDelimitacion = OrigenDelimitacion.DISTRITOS;

    @Column(name = "creado_por")
    private UUID creadoPor;

    @Column(name = "actualizado_por")
    private UUID actualizadoPor;

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

    public EstadoZona getEstado() {
        return estado;
    }

    public void setEstado(EstadoZona estado) {
        this.estado = estado;
    }

    public OrigenDelimitacion getOrigenDelimitacion() {
        return origenDelimitacion;
    }

    public void setOrigenDelimitacion(OrigenDelimitacion origenDelimitacion) {
        this.origenDelimitacion = origenDelimitacion;
    }

    public UUID getCreadoPor() {
        return creadoPor;
    }

    public void setCreadoPor(UUID creadoPor) {
        this.creadoPor = creadoPor;
    }

    public UUID getActualizadoPor() {
        return actualizadoPor;
    }

    public void setActualizadoPor(UUID actualizadoPor) {
        this.actualizadoPor = actualizadoPor;
    }
}
