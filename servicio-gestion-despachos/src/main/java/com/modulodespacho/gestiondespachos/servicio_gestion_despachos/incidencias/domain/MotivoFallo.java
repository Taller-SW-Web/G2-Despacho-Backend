package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain;

public record MotivoFallo(String codigo, String etiqueta, boolean seleccionableEnCampo,
        boolean consumeIntento, boolean activo) {}
