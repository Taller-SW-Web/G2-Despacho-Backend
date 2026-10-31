package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.Instant;

public interface LiberadorCapacidad {
    Resultado liberar(LiberacionesPendientes.Envio envio);
    record Resultado(boolean confirmada, boolean requiereRevision, String codigo, Instant liberadaEn) {
        public static Resultado fallo(boolean revision, String codigo) { return new Resultado(false, revision, codigo, null); }
    }
}
