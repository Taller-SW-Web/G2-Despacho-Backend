package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface AlmacenAdelantos {
    ModelosAdelanto.Respuesta adelantar(
            UUID id,
            String clave,
            int version,
            String motivo,
            String actor,
            LocalDate hoy,
            Instant ahora);
}
