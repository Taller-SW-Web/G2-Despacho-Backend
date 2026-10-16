package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoZona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Zona;

public interface ZonaRepositorioJpa extends JpaRepository<Zona, UUID> {

    Optional<Zona> findByNombreIgnoreCase(String nombre);

    boolean existsByNombreIgnoreCaseAndIdNot(String nombre, UUID id);

    @Query("""
            SELECT z FROM Zona z
            WHERE (:nombre IS NULL OR lower(z.nombre) LIKE lower(concat('%', :nombre, '%')))
              AND (:estado IS NULL OR z.estado = :estado)
              AND (:distrito IS NULL OR EXISTS (
                    SELECT 1 FROM ZonaDistrito c
                    WHERE c.zona = z AND lower(c.distrito.nombre) = lower(:distrito)))
            """)
    Page<Zona> buscar(@Param("nombre") String nombre, @Param("distrito") String distrito,
            @Param("estado") EstadoZona estado, Pageable pageable);

    @Query("""
            SELECT DISTINCT c FROM ZonaDistrito c
            JOIN FETCH c.zona z
            LEFT JOIN FETCH c.distrito
            WHERE z.estado = 'ACTIVO' AND z.id <> :zonaId
              AND c.distrito.id IN :distritos
            """)
    List<com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ZonaDistrito> buscarConflictoPorDistritos(
            @Param("zonaId") UUID zonaId, @Param("distritos") Collection<UUID> distritos);

    @Query("""
            SELECT DISTINCT c FROM ZonaDistrito c
            JOIN FETCH c.zona z
            LEFT JOIN FETCH c.distrito
            WHERE z.estado = 'ACTIVO' AND z.id <> :zonaId
              AND c.codigoPostal IN :codigosPostales
            """)
    List<com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ZonaDistrito> buscarConflictoPorCodigos(
            @Param("zonaId") UUID zonaId, @Param("codigosPostales") Collection<String> codigosPostales);
}
