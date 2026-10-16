package com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.application;

import java.util.List;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.domain.DatosIncidenciasOperativas;

public interface LectorIncidenciasOperativas {
    DatosIncidenciasOperativas consultar(List<String> idsRepartidor, List<String> idsJornada);
}
