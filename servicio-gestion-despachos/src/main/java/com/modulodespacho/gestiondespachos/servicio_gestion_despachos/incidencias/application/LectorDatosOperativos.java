package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.util.Collection;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos;

/** Las jornadas se consultan para toda la base pendiente; los nombres pueden pedirse por página. */
public interface LectorDatosOperativos {
    DatosOperativos consultar(Collection<String> idsRepartidor, Collection<String> idsJornada);
}
