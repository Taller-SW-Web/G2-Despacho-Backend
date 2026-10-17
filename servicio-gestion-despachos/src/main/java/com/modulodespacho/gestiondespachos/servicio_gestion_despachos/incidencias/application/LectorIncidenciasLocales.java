package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.LocalDate;
import java.util.List;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.IncidenciaListadoLocal;

public interface LectorIncidenciasLocales {
    /**
     * Lee toda la base FALLIDO con motivo opcional y días inclusivos en America/Lima.
     * La recepción y la paginación se aplican después de calcular los indicadores.
     */
    List<IncidenciaListadoLocal> consultarBase(String motivo, LocalDate fechaDesde, LocalDate fechaHasta);
}
