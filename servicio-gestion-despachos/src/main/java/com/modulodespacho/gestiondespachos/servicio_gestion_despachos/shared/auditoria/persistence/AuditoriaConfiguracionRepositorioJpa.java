package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.persistence;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.domain.AuditoriaConfiguracion;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriaConfiguracionRepositorioJpa
        extends JpaRepository<AuditoriaConfiguracion, UUID> {}
