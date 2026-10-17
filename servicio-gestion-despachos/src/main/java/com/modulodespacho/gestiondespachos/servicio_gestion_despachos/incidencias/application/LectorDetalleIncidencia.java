package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

public interface LectorDetalleIncidencia {
    /** Devuelve una instantánea local completa antes de efectuar cualquier lectura HTTP. */
    DetalleIncidencia consultar(String idDespacho);
}
