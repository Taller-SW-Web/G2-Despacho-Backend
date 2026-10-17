package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.domain.AuditoriaConfiguracion;

public interface AuditoriaConfiguracionRepositorioJpa extends JpaRepository<AuditoriaConfiguracion, UUID> {
}
