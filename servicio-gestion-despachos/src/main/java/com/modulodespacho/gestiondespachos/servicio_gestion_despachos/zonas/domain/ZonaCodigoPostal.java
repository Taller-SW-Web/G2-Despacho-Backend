package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "zonas_codigos_postales")
public class ZonaCodigoPostal extends EntidadConFechas {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zona_id", nullable = false)
    private Zona zona;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "distrito_id", nullable = false)
    private Distrito distrito;

    @Column(name = "codigo_postal", nullable = false)
    private String codigoPostal;

    public static ZonaCodigoPostal de(Distrito distrito, String codigoPostal) {
        ZonaCodigoPostal cobertura = new ZonaCodigoPostal();
        cobertura.id = UUID.randomUUID();
        cobertura.distrito = distrito;
        cobertura.codigoPostal = codigoPostal;
        return cobertura;
    }

    public UUID getId() {
        return id;
    }

    public Zona getZona() {
        return zona;
    }

    public void setZona(Zona zona) {
        this.zona = zona;
    }

    public Distrito getDistrito() {
        return distrito;
    }

    public String getCodigoPostal() {
        return codigoPostal;
    }
}
