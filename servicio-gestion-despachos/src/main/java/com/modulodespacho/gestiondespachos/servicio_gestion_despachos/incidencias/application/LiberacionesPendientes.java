package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LiberacionesPendientes {
    Optional<Envio> reclamar(Instant ahora);
    void confirmar(Envio envio, Instant ahora, Instant liberadaEn);
    void reintentar(Envio envio, Instant ahora, Instant siguiente, boolean revision, String codigo);
    record Envio(UUID id, UUID token, String despacho, String incidencia, String reserva, String jornada, int intento,
            boolean requiereRevision, boolean contextoValido) {}
}
