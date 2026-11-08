package com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.application;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.domain.DatosIncidenciasOperativas;
import java.util.List;

public interface LectorIncidenciasOperativas {
    DatosIncidenciasOperativas consultar(List<String> idsRepartidor, List<String> idsJornada);
}
