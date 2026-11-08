package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.TarifaZona;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TarifaZonaRepositorioJpa extends JpaRepository<TarifaZona, UUID> {

    Optional<TarifaZona> findFirstByZonaIdAndActivaTrue(UUID zonaId);

    List<TarifaZona> findByZonaIdInAndActivaTrue(Collection<UUID> zonaIds);

    List<TarifaZona> findByZonaIdOrderByCreadoEnDescIdDesc(UUID zonaId);
}
