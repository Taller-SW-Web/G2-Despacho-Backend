package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Distrito;

public interface DistritoRepositorioJpa extends JpaRepository<Distrito, UUID> {

    @Query("SELECT d FROM Distrito d WHERE lower(d.nombre) IN :nombres")
    List<Distrito> buscarPorNombres(@Param("nombres") Collection<String> nombres);
}
