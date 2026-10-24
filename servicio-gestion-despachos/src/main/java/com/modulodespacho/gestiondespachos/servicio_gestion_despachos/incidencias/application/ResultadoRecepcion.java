package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

public record ResultadoRecepcion(String idDespacho, String idIncidencia, String estado, int versionDespacho,
        DetalleIncidencia.Recepcion recepcion) {}
