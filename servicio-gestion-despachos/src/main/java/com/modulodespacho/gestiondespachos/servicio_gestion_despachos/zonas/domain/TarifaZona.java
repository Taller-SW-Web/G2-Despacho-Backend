package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "tarifas_zona")
public class TarifaZona extends EntidadConFechas {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zona_id", nullable = false)
    private Zona zona;

    @Column(name = "tarifa_base", nullable = false)
    private BigDecimal tarifaBase;

    @Column(name = "peso_base_kg")
    private BigDecimal pesoBaseKg;

    @Column(name = "recargo_kg_adicional")
    private BigDecimal recargoKgAdicional;

    @Column(name = "factor_volumetrico")
    private BigDecimal factorVolumetrico;

    @Column(name = "moneda")
    private String moneda;

    @Column(name = "plazo_min_dias")
    private Integer plazoMinDias;

    @Column(name = "plazo_max_dias")
    private Integer plazoMaxDias;

    @Column(name = "activa", nullable = false)
    private boolean activa = true;

    @Column(name = "creado_por")
    private UUID creadoPor;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Zona getZona() {
        return zona;
    }

    public void setZona(Zona zona) {
        this.zona = zona;
    }

    public BigDecimal getTarifaBase() {
        return tarifaBase;
    }

    public void setTarifaBase(BigDecimal tarifaBase) {
        this.tarifaBase = tarifaBase;
    }

    public BigDecimal getPesoBaseKg() {
        return pesoBaseKg;
    }

    public void setPesoBaseKg(BigDecimal pesoBaseKg) {
        this.pesoBaseKg = pesoBaseKg;
    }

    public BigDecimal getRecargoKgAdicional() {
        return recargoKgAdicional;
    }

    public void setRecargoKgAdicional(BigDecimal recargoKgAdicional) {
        this.recargoKgAdicional = recargoKgAdicional;
    }

    public BigDecimal getFactorVolumetrico() {
        return factorVolumetrico;
    }

    public void setFactorVolumetrico(BigDecimal factorVolumetrico) {
        this.factorVolumetrico = factorVolumetrico;
    }

    public String getMoneda() {
        return moneda;
    }

    public void setMoneda(String moneda) {
        this.moneda = moneda;
    }

    public Integer getPlazoMinDias() {
        return plazoMinDias;
    }

    public void setPlazoMinDias(Integer plazoMinDias) {
        this.plazoMinDias = plazoMinDias;
    }

    public Integer getPlazoMaxDias() {
        return plazoMaxDias;
    }

    public void setPlazoMaxDias(Integer plazoMaxDias) {
        this.plazoMaxDias = plazoMaxDias;
    }

    public boolean isActiva() {
        return activa;
    }

    public void setActiva(boolean activa) {
        this.activa = activa;
    }

    public UUID getCreadoPor() {
        return creadoPor;
    }

    public void setCreadoPor(UUID creadoPor) {
        this.creadoPor = creadoPor;
    }
}
