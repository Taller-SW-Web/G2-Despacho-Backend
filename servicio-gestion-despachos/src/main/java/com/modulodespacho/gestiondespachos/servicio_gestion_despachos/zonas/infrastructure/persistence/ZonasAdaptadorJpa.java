package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.PaginaZonasCruda;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RepositorioZonas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoZona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Zona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ZonaDistrito;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ZonaCodigoPostal;

@Component
@Profile("!integracion-local")
public class ZonasAdaptadorJpa implements RepositorioZonas {

    private final ZonaRepositorioJpa zonas;
    private final ZonaDistritoRepositorioJpa coberturas;
    private final ZonaCodigoPostalRepositorioJpa codigosPostales;

    public ZonasAdaptadorJpa(ZonaRepositorioJpa zonas, ZonaDistritoRepositorioJpa coberturas,
            ZonaCodigoPostalRepositorioJpa codigosPostales) {
        this.zonas = zonas;
        this.coberturas = coberturas;
        this.codigosPostales = codigosPostales;
    }

    @Override
    public Zona guardar(Zona zona) {
        return zonas.save(zona);
    }

    @Override
    public void agregarCoberturas(Zona zona, List<ZonaDistrito> nuevas, List<ZonaCodigoPostal> nuevosCodigos) {
        nuevas.forEach(cobertura -> cobertura.setZona(zona));
        coberturas.saveAll(nuevas);
        nuevosCodigos.forEach(codigo -> codigo.setZona(zona));
        codigosPostales.saveAll(nuevosCodigos);
    }

    @Override
    public void reemplazarCoberturas(UUID zonaId, List<ZonaDistrito> nuevas, List<ZonaCodigoPostal> nuevosCodigos) {
        codigosPostales.eliminarPorZona(zonaId);
        coberturas.eliminarPorZona(zonaId);
        Zona referencia = zonas.getReferenceById(zonaId);
        agregarCoberturas(referencia, nuevas, nuevosCodigos);
    }

    @Override
    public Optional<Zona> buscarPorId(UUID id) {
        return zonas.findById(id);
    }

    @Override
    public Optional<Zona> buscarPorNombre(String nombre) {
        return zonas.findByNombreIgnoreCase(nombre);
    }

    @Override
    public boolean existeNombre(String nombre, UUID excluirId) {
        if (excluirId == null) {
            return zonas.findByNombreIgnoreCase(nombre).isPresent();
        }
        return zonas.existsByNombreIgnoreCaseAndIdNot(nombre, excluirId);
    }

    @Override
    public PaginaZonasCruda buscar(String nombre, String distrito, EstadoZona estado, int pagina, int tamano) {
        PageRequest pageable = PageRequest.of(pagina - 1, tamano, Sort.by(Sort.Direction.ASC, "nombre"));
        Page<Zona> resultado = zonas.buscar(nombre, distrito, estado, pageable);
        return new PaginaZonasCruda(resultado.getContent(), pagina, tamano,
                resultado.getTotalElements(), resultado.getTotalPages());
    }

    @Override
    public Optional<Zona> resolverCobertura(String distrito, String codigoPostal) {
        Optional<Zona> porDistrito = distrito == null ? Optional.empty()
                : coberturas.buscarCobertura(distrito).stream().map(ZonaDistrito::getZona).findFirst();
        if (porDistrito.isPresent() || codigoPostal == null) {
            return porDistrito;
        }
        return codigosPostales.buscarCobertura(codigoPostal).stream().findFirst();
    }

    @Override
    public List<Zona> buscarConflicto(UUID zonaId, Collection<UUID> distritos,
            Collection<String> codigosPostales) {
        List<Zona> conflictos = new ArrayList<>();
        if (distritos != null && !distritos.isEmpty()) {
            conflictos.addAll(zonas.buscarConflictoPorDistritos(zonaId, distritos).stream()
                    .map(ZonaDistrito::getZona).toList());
        }
        if (codigosPostales != null && !codigosPostales.isEmpty()) {
            conflictos.addAll(this.codigosPostales.buscarConflicto(zonaId, codigosPostales));
        }
        return conflictos;
    }

    @Override
    public List<ZonaDistrito> coberturasDe(Collection<UUID> zonaIds) {
        if (zonaIds.isEmpty()) {
            return List.of();
        }
        return coberturas.buscarPorZonas(zonaIds);
    }

    @Override
    public List<ZonaCodigoPostal> codigosPostalesDe(Collection<UUID> zonaIds) {
        return zonaIds.isEmpty() ? List.of() : codigosPostales.buscarPorZonas(zonaIds);
    }
}
