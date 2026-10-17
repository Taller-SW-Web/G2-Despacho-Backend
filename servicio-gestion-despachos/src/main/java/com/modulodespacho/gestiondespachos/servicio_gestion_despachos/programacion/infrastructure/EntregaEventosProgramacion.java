package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Outbox: un cambio local confirmado nunca se revierte por una comunicación fallida. */
public final class EntregaEventosProgramacion {
    private final JdbcClient sql;
    private final JsonMapper json;
    private final RestClient ventas;
    private final Supplier<String> tokenVentas;
    private final RestClient operacion;
    private final Supplier<String> tokenOperacion;
    private final boolean operacionSinAutenticacion;
    public EntregaEventosProgramacion(JdbcClient sql, JsonMapper json, RestClient ventas, Supplier<String> tokenVentas,
            RestClient operacion, Supplier<String> tokenOperacion) {
        this(sql, json, ventas, tokenVentas, operacion, tokenOperacion, false);
    }
    public EntregaEventosProgramacion(JdbcClient sql, JsonMapper json, RestClient ventas, Supplier<String> tokenVentas,
            RestClient operacion, Supplier<String> tokenOperacion, boolean operacionSinAutenticacion) {
        this.sql = sql; this.json = json; this.ventas = ventas; this.tokenVentas = tokenVentas;
        this.operacion = operacion; this.tokenOperacion = tokenOperacion;
        this.operacionSinAutenticacion = operacionSinAutenticacion;
    }
    public void ejecutar() {
        var pendientes = sql.sql("""
                SELECT id FROM eventos_salientes WHERE estado_envio = 'PENDIENTE' AND proximo_intento_en <= now()
                AND (bloqueada_hasta IS NULL OR bloqueada_hasta < now()) ORDER BY creado_en, id LIMIT 20
                """).query(UUID.class).list();
        for (UUID id : pendientes) enviar(id);
    }
    private void enviar(UUID id) {
        UUID bloqueo = UUID.randomUUID();
        var reclamada = sql.sql("""
                UPDATE eventos_salientes SET bloqueada_hasta = now() + interval '60 seconds', bloqueo_id = :bloqueo
                WHERE id = :id AND estado_envio = 'PENDIENTE' AND (bloqueada_hasta IS NULL OR bloqueada_hasta < now())
                RETURNING destino, payload::text, intentos_envio
                """).param("id", id).param("bloqueo", bloqueo)
                .query((fila, indice) -> new Evento(fila.getString(1), fila.getString(2), fila.getInt(3))).optional();
        if (reclamada.isEmpty()) return;
        Evento evento = reclamada.get();
        try {
            boolean haciaVentas = "VENTAS".equals(evento.destino());
            boolean requiereCredencial = haciaVentas || !operacionSinAutenticacion;
            String credencial = requiereCredencial ? (haciaVentas ? tokenVentas : tokenOperacion).get() : null;
            if (requiereCredencial && (credencial == null || credencial.isBlank())) throw new IllegalStateException("Credencial de servicio no disponible");
            String ruta = haciaVentas ? "/api/v1/integraciones/despachos/eventos"
                    : "/internal/v1/proyecciones/despachos/" + json.readTree(evento.payload()).path("idDespacho").asText();
            var respuesta = (haciaVentas ? ventas : operacion).post().uri(ruta).headers(cabeceras -> {
                        if (requiereCredencial) cabeceras.setBearerAuth(credencial);
                    })
                    .contentType(MediaType.APPLICATION_JSON).body(evento.payload()).retrieve().toBodilessEntity();
            if (!respuesta.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("El servicio no confirmó la entrega");
            sql.sql("""
                    UPDATE eventos_salientes SET estado_envio = 'ENVIADO', enviado_en = now(), intentos_envio = intentos_envio + 1,
                        bloqueada_hasta = NULL, bloqueo_id = NULL, ultimo_error = NULL, actualizado_en = now()
                    WHERE id = :id AND bloqueo_id = :bloqueo
                    """).param("id", id).param("bloqueo", bloqueo).update();
        } catch (RuntimeException error) {
            int intentos = evento.intentos() + 1;
            // Ventas: un envío inicial y hasta cinco reintentos. Proyección interna: recuperación continua.
            boolean agotado = "VENTAS".equals(evento.destino()) && intentos >= 6;
            sql.sql("""
                    UPDATE eventos_salientes SET estado_envio = :estado, intentos_envio = :intentos,
                        proximo_intento_en = now() + make_interval(secs => :espera), bloqueada_hasta = NULL, bloqueo_id = NULL,
                        ultimo_error = 'No se confirmó la entrega al servicio destinatario', actualizado_en = now()
                    WHERE id = :id AND bloqueo_id = :bloqueo
                    """).param("id", id).param("bloqueo", bloqueo).param("estado", agotado ? "ENVIO_FALLIDO" : "PENDIENTE")
                    .param("intentos", intentos).param("espera", Math.min(3600, 30L << Math.min(intentos - 1, 7))).update();
        }
    }
    private record Evento(String destino, String payload, int intentos) {}
}
