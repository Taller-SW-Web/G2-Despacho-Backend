package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RegistroConflictos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ConflictoZonaLog;

@Component
@Profile("!integracion-local")
public class ConflictosAdaptadorJpa implements RegistroConflictos {

    private final ConflictoZonaLogRepositorioJpa conflictos;

    public ConflictosAdaptadorJpa(ConflictoZonaLogRepositorioJpa conflictos) {
        this.conflictos = conflictos;
    }

    @Override
    public void registrar(ConflictoZonaLog conflicto) {
        conflictos.save(conflicto);
    }
}
