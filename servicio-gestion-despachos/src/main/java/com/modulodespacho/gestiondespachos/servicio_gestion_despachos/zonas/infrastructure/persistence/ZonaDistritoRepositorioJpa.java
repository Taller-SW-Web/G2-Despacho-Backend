package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ZonaDistrito;

public interface ZonaDistritoRepositorioJpa extends JpaRepository<ZonaDistrito, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ZonaDistrito c WHERE c.zona.id = :zonaId")
    void eliminarPorZona(@Param("zonaId") UUID zonaId);

    @Query("SELECT c FROM ZonaDistrito c LEFT JOIN FETCH c.distrito WHERE c.zona.id IN :zonaIds")
    List<ZonaDistrito> buscarPorZonas(@Param("zonaIds") Collection<UUID> zonaIds);

    @Query("""
            SELECT DISTINCT c FROM ZonaDistrito c
            JOIN FETCH c.zona z
            LEFT JOIN FETCH c.distrito d
            WHERE z.estado = 'ACTIVO'
              AND lower(d.nombre) = lower(:distrito)
            """)
    List<ZonaDistrito> buscarCobertura(@Param("distrito") String distrito);
}
