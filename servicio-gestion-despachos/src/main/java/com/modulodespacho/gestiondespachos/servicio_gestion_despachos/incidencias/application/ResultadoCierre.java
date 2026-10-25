package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.Instant;

public record ResultadoCierre(String idDespacho, String idIncidencia, String estado, int versionDespacho,
        String motivo, String observacion, int numeroIntentos, Instant cerradoEn, String idEvento) {}
