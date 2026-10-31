package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

public record SolicitudRecepcion(String idIncidencia, int versionDespacho, boolean selloIntacto, String observacion) {}
