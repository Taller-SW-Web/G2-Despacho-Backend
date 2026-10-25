package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.LocalDate;

public record SolicitudReprogramacion(String idIncidencia, int versionDespacho,
        LocalDate nuevaFechaProgramada, String observacion) {}
