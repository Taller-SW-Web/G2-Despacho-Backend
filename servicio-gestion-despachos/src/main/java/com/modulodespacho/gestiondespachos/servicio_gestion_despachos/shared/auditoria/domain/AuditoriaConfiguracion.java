package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.AccionAuditoria;

@Entity
@Table(name = "auditoria_configuracion")
public class AuditoriaConfiguracion {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "entidad", nullable = false)
    private String entidad;

    @Column(name = "entidad_id", nullable = false)
    private String entidadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "accion", nullable = false)
    private AccionAuditoria accion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datos_anteriores")
    private String datosAnteriores;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datos_nuevos")
    private String datosNuevos;

    @Column(name = "usuario_id", nullable = false)
    private String usuarioId;

    @Column(name = "registrado_en", nullable = false)
    private Instant registradoEn;

    public static AuditoriaConfiguracion registro(String entidad, String entidadId, AccionAuditoria accion,
            String datosAnteriores, String datosNuevos, String usuarioId) {
        AuditoriaConfiguracion auditoria = new AuditoriaConfiguracion();
        auditoria.id = UUID.randomUUID();
        auditoria.entidad = entidad;
        auditoria.entidadId = entidadId;
        auditoria.accion = accion;
        auditoria.datosAnteriores = datosAnteriores;
        auditoria.datosNuevos = datosNuevos;
        auditoria.usuarioId = usuarioId;
        auditoria.registradoEn = Instant.now();
        return auditoria;
    }

    public UUID getId() {
        return id;
    }

    public String getEntidad() {
        return entidad;
    }

    public String getEntidadId() {
        return entidadId;
    }

    public AccionAuditoria getAccion() {
        return accion;
    }

    public String getDatosAnteriores() {
        return datosAnteriores;
    }

    public String getDatosNuevos() {
        return datosNuevos;
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public Instant getRegistradoEn() {
        return registradoEn;
    }
}
