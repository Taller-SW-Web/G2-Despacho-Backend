package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.Instant;
import java.time.LocalDate;

public record ResultadoReprogramacion(String idDespacho, String idIncidencia, String estado, int versionDespacho,
        LocalDate fechaProgramada, int numeroIntentos, String observacion, Instant reprogramadoEn, String idEvento) {}
