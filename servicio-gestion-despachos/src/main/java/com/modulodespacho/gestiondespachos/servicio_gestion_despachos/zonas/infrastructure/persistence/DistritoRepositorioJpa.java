package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Distrito;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoDistrito;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DistritoRepositorioJpa extends JpaRepository<Distrito, UUID> {

    @Query("SELECT d FROM Distrito d WHERE lower(d.nombre) IN :nombres")
    List<Distrito> buscarPorNombres(@Param("nombres") Collection<String> nombres);

    @Query(
            """
            SELECT d FROM Distrito d
            WHERE (lower(d.provincia) = 'lima' OR lower(d.departamento) = 'callao')
              AND (cast(:nombre as string) IS NULL
                   OR lower(d.nombre) LIKE lower(concat('%', cast(:nombre as string), '%')))
              AND (:estado IS NULL OR d.estado = :estado)
            ORDER BY d.nombre ASC, d.id ASC
            """)
    List<Distrito> catalogo(@Param("nombre") String nombre, @Param("estado") EstadoDistrito estado);

    @Query(
            "SELECT count(d) > 0 FROM Distrito d WHERE lower(trim(d.nombre)) = lower(:nombre) AND (:id IS NULL OR d.id <> :id)")
    boolean existeNombre(@Param("nombre") String nombre, @Param("id") UUID excluirId);

    @Query(
            "SELECT count(d) > 0 FROM Distrito d WHERE d.ubigeo = :ubigeo AND (:id IS NULL OR d.id <> :id)")
    boolean existeUbigeo(@Param("ubigeo") String ubigeo, @Param("id") UUID excluirId);

    @Query(
            value =
                    """
            SELECT EXISTS(SELECT 1 FROM zonas_distritos WHERE distrito_id = :id)
                OR EXISTS(SELECT 1 FROM zonas_codigos_postales WHERE distrito_id = :id)
            """,
            nativeQuery = true)
    boolean tieneAsociaciones(@Param("id") UUID id);
}
