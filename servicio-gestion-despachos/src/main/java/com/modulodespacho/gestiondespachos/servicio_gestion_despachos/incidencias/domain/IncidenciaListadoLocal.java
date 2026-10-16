package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain;

import java.time.Instant;

/** Proyección local de la incidencia vigente; no contiene información privada del detalle. */
public record IncidenciaListadoLocal(
        String idDespacho,
        String idIncidencia,
        int versionDespacho,
        String codigoRastreoInterno,
        Instant fechaIncidencia,
        Motivo motivo,
        int numeroIntentos,
        int maximoIntentos,
        boolean consumeIntento,
        String idRepartidor,
        String idJornada,
        boolean recibidoEnCentro,
        boolean tieneEvidencia,
        boolean pedidoAnulado) {

    public record Motivo(String codigo, String etiqueta) {}
}
