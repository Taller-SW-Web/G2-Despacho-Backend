package com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.domain;

import java.time.Instant;
import java.util.List;

public record DatosIncidenciasOperativas(List<Repartidor> repartidores, List<Jornada> jornadas, Instant consultadoEn) {
    public record Repartidor(String idRepartidor, String nombre) {}
    public record Jornada(String idJornada, String estado, Instant cerradaEn) {}
}
