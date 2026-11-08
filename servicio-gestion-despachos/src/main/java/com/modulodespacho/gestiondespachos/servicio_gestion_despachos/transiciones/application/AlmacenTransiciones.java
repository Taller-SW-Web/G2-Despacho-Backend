package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.transiciones.application;

import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.transiciones.application.ModelosTransicion.*;

import java.time.Instant;
import java.util.UUID;

public interface AlmacenTransiciones {
    /** Aplica la transición, su historial, incidencia y eventos en una transacción idempotente por clave. */
    RespuestaTransicion registrar(
            UUID idDespacho,
            String clave,
            SolicitudTransicion solicitud,
            String actor,
            Instant ahora);
}
