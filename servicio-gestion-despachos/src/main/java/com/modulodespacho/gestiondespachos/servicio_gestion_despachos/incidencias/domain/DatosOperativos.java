package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lectura de Operación por identificador; no sustituye los datos locales de la incidencia. */
public record DatosOperativos(Map<String, Repartidor> repartidores, Map<String, Jornada> jornadas) {

    public DatosOperativos {
        repartidores = Collections.unmodifiableMap(new LinkedHashMap<>(repartidores));
        jornadas = Collections.unmodifiableMap(new LinkedHashMap<>(jornadas));
    }

    public record Repartidor(String idRepartidor, String nombre) {}

    public enum EstadoJornada { ACTIVA, CERRADA, DESCONOCIDA }

    public record Jornada(String idJornada, EstadoJornada estado, Instant cerradaEn, Instant consultadaEn) {
        public static Jornada desconocida(String idJornada, Instant consultadaEn) {
            return new Jornada(idJornada, EstadoJornada.DESCONOCIDA, null, consultadaEn);
        }
    }
}
