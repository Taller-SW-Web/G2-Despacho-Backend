package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.util.List;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.MotivoFallo;

public interface LectorCatalogoMotivosFallo {
    /** Catálogo completo, incluidos inactivos, ordenado por código. */
    List<MotivoFallo> consultarTodos();
}
