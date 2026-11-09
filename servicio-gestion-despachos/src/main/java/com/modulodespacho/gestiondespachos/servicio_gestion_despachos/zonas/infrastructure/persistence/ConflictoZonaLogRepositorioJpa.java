package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ConflictoZonaLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConflictoZonaLogRepositorioJpa extends JpaRepository<ConflictoZonaLog, UUID> {}
