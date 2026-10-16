package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

public interface CatalogoMotivosFallo {
    /** Incluye códigos inactivos que se conservan para las consultas históricas. */
    boolean existeCodigo(String codigo);
}
