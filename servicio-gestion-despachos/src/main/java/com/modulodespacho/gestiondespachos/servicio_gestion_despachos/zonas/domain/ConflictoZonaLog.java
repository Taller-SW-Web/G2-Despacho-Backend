package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "zona_conflictos_log")
public class ConflictoZonaLog extends EntidadConFechas {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "nombre_intentado")
    private String nombreIntentado;

    @Column(name = "zona_conflicto_id")
    private UUID zonaConflictoId;

    @Column(name = "motivo")
    private String motivo;

    @Column(name = "detalle")
    private String detalle;

    @Column(name = "intentado_en", nullable = false)
    private Instant intentadoEn;

    @Column(name = "intentado_por")
    private UUID intentadoPor;

    public static ConflictoZonaLog de(String nombreIntentado, UUID zonaConflictoId, String motivo,
            String detalle, UUID intentadoPor) {
        ConflictoZonaLog conflicto = new ConflictoZonaLog();
        conflicto.id = UUID.randomUUID();
        conflicto.nombreIntentado = nombreIntentado;
        conflicto.zonaConflictoId = zonaConflictoId;
        conflicto.motivo = motivo;
        conflicto.detalle = detalle;
        conflicto.intentadoEn = Instant.now();
        conflicto.intentadoPor = intentadoPor;
        return conflicto;
    }

    public UUID getId() {
        return id;
    }

    public String getNombreIntentado() {
        return nombreIntentado;
    }

    public UUID getZonaConflictoId() {
        return zonaConflictoId;
    }

    public String getMotivo() {
        return motivo;
    }

    public String getDetalle() {
        return detalle;
    }

    public Instant getIntentadoEn() {
        return intentadoEn;
    }

    public UUID getIntentadoPor() {
        return intentadoPor;
    }
}
