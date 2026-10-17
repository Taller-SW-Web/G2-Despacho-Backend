package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoZona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Zona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ZonaDistrito;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ZonaCodigoPostal;

public interface RepositorioZonas {

    Zona guardar(Zona zona);

    void agregarCoberturas(Zona zona, List<ZonaDistrito> coberturas, List<ZonaCodigoPostal> codigosPostales);

    void reemplazarCoberturas(UUID zonaId, List<ZonaDistrito> coberturas, List<ZonaCodigoPostal> codigosPostales);

    Optional<Zona> buscarPorId(UUID id);

    Optional<Zona> buscarPorNombre(String nombre);

    boolean existeNombre(String nombre, UUID excluirId);

    PaginaZonasCruda buscar(String nombre, String distrito, EstadoZona estado, int pagina, int tamano);

    Optional<Zona> resolverCobertura(String distrito, String codigoPostal);

    List<Zona> buscarConflicto(UUID zonaId, Collection<UUID> distritos, Collection<String> codigosPostales);

    List<ZonaDistrito> coberturasDe(Collection<UUID> zonaIds);

    List<ZonaCodigoPostal> codigosPostalesDe(Collection<UUID> zonaIds);
}
