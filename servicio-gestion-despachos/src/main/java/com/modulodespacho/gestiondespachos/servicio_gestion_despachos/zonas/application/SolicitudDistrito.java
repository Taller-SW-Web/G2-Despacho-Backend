package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

public record SolicitudDistrito(
        String nombre,
        String provincia,
        String departamento,
        String ubigeo,
        Long versionDistrito) {}
