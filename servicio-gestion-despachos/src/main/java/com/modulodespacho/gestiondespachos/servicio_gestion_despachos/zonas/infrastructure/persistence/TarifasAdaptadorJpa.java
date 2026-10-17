package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RepositorioTarifas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.TarifaZona;

@Component
@Profile("!integracion-local")
public class TarifasAdaptadorJpa implements RepositorioTarifas {

    private final TarifaZonaRepositorioJpa tarifas;

    public TarifasAdaptadorJpa(TarifaZonaRepositorioJpa tarifas) {
        this.tarifas = tarifas;
    }

    @Override
    public Optional<TarifaZona> vigente(UUID zonaId) {
        return tarifas.findFirstByZonaIdAndActivaTrue(zonaId);
    }

    @Override
    public Map<UUID, TarifaZona> vigentesDe(Collection<UUID> zonaIds) {
        if (zonaIds.isEmpty()) {
            return Map.of();
        }
        List<TarifaZona> encontradas = tarifas.findByZonaIdInAndActivaTrue(zonaIds);
        Map<UUID, TarifaZona> porZona = new LinkedHashMap<>();
        for (TarifaZona tarifa : encontradas) {
            porZona.put(tarifa.getZona().getId(), tarifa);
        }
        return porZona;
    }

    @Override
    public TarifaZona guardar(TarifaZona tarifa) {
        return tarifas.save(tarifa);
    }

    @Override
    public void desactivarVigente(UUID zonaId) {
        tarifas.findFirstByZonaIdAndActivaTrue(zonaId).ifPresent(tarifa -> {
            tarifa.setActiva(false);
            tarifas.save(tarifa);
        });
    }
}
