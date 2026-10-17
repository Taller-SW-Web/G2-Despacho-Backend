package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.infrastructure;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.AccionAuditoria;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.domain.AuditoriaConfiguracion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.persistence.AuditoriaConfiguracionRepositorioJpa;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RegistroAuditoria;

@Component
@Profile("!integracion-local")
public class AuditoriaAdaptador implements RegistroAuditoria {

    private final AuditoriaConfiguracionRepositorioJpa auditorias;

    public AuditoriaAdaptador(AuditoriaConfiguracionRepositorioJpa auditorias) {
        this.auditorias = auditorias;
    }

    @Override
    public void registrar(String entidad, String entidadId, AccionAuditoria accion,
            String datosAnterioresJson, String datosNuevosJson, String usuarioId) {
        auditorias.save(AuditoriaConfiguracion.registro(entidad, entidadId, accion,
                datosAnterioresJson, datosNuevosJson, usuarioId));
    }
}
