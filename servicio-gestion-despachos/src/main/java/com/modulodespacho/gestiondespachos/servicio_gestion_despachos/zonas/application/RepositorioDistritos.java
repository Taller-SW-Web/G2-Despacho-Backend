package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Distrito;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoDistrito;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface RepositorioDistritos {

    /** Devuelve los distritos indexados por nombre en minusculas. */
    Map<String, Distrito> porNombres(Collection<String> nombres);

    /** Devuelve el catalogo de distritos de Lima Metropolitana y Callao, con filtro opcional por nombre. */
    List<Distrito> catalogo(String nombre, EstadoDistrito estado);

    Optional<Distrito> buscarPorId(UUID id);

    Distrito guardar(Distrito distrito);

    boolean tieneAsociaciones(UUID id);

    boolean existeNombre(String nombre, UUID excluirId);

    boolean existeUbigeo(String ubigeo, UUID excluirId);
}
