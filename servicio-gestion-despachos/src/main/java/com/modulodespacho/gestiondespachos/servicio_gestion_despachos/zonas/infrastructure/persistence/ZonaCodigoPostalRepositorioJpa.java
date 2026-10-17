package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Zona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ZonaCodigoPostal;

public interface ZonaCodigoPostalRepositorioJpa extends JpaRepository<ZonaCodigoPostal, UUID> {

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM ZonaCodigoPostal c WHERE c.zona.id = :zonaId")
    void eliminarPorZona(@Param("zonaId") UUID zonaId);

    @Query("SELECT c FROM ZonaCodigoPostal c JOIN FETCH c.distrito WHERE c.zona.id IN :zonaIds")
    List<ZonaCodigoPostal> buscarPorZonas(@Param("zonaIds") Collection<UUID> zonaIds);

    @Query("""
            SELECT DISTINCT c.zona FROM ZonaCodigoPostal c
            WHERE c.zona.estado = 'ACTIVO' AND c.codigoPostal = :codigoPostal
            """)
    List<Zona> buscarCobertura(@Param("codigoPostal") String codigoPostal);

    @Query("""
            SELECT DISTINCT c.zona FROM ZonaCodigoPostal c
            WHERE c.zona.estado = 'ACTIVO' AND c.zona.id <> :zonaId
              AND c.codigoPostal IN :codigosPostales
            """)
    List<Zona> buscarConflicto(@Param("zonaId") UUID zonaId,
            @Param("codigosPostales") Collection<String> codigosPostales);
}
