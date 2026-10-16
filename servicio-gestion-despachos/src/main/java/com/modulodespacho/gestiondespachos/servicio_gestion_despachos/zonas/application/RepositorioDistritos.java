package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.Collection;
import java.util.Map;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Distrito;

public interface RepositorioDistritos {

    /** Devuelve los distritos indexados por nombre en minusculas. */
    Map<String, Distrito> porNombres(Collection<String> nombres);
}
