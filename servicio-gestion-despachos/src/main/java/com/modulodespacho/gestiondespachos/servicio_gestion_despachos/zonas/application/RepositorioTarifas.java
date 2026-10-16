package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.TarifaZona;

public interface RepositorioTarifas {

    Optional<TarifaZona> vigente(UUID zonaId);

    Map<UUID, TarifaZona> vigentesDe(Collection<UUID> zonaIds);

    TarifaZona guardar(TarifaZona tarifa);

    void desactivarVigente(UUID zonaId);
}
