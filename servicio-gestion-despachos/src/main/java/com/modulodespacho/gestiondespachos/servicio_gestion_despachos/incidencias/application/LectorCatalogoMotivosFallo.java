package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.MotivoFallo;
import java.util.List;

public interface LectorCatalogoMotivosFallo {
    /** Catálogo completo, incluidos inactivos, ordenado por código. */
    List<MotivoFallo> consultarTodos();
}
