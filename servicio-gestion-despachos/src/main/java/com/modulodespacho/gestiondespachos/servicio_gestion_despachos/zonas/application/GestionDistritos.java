package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!integracion-local")
public class GestionDistritos {

    private final RepositorioDistritos repositorioDistritos;

    public GestionDistritos(RepositorioDistritos repositorioDistritos) {
        this.repositorioDistritos = repositorioDistritos;
    }

    @Transactional(readOnly = true)
    public List<DistritoCatalogoVista> catalogo(String nombre) {
        return repositorioDistritos.catalogo(nombre).stream()
                .map(distrito -> new DistritoCatalogoVista(distrito.getId(), distrito.getNombre(),
                        distrito.getProvincia(), distrito.getDepartamento(), distrito.getUbigeo()))
                .toList();
    }
}
