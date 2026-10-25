package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Captura F-04/RT-02 en la transacción del productor; no abre otra transacción. */
@Repository
@Profile("!integracion-local")
public class HistorialIncidenciasJdbc {
    private final JdbcClient sql;
    private final JsonMapper json;
    public HistorialIncidenciasJdbc(JdbcClient sql, JsonMapper json) { this.sql = sql; this.json = json; }
    public UUID registrar(String despacho, String operacion, String anterior, String nuevo, String actor,
            String observacion, Map<String, Object> datos, Instant ahora) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("El historial debe capturarse en la transacción de la operación");
        UUID id = UUID.randomUUID();
        sql.sql("""
                INSERT INTO historial_estados_despacho (id, despacho_id, tipo_operacion, estado_anterior, estado_nuevo,
                  funcionalidad_origen, ejecutor_tipo, ejecutor_id, origen, observaciones, datos, registrado_en)
                VALUES (:id, CAST(:despacho AS uuid), :operacion, :anterior, :nuevo,
                  'F-04', 'USUARIO', :actor, 'MANUAL', :observacion, CAST(:datos AS jsonb), :fecha)
                """).param("id", id).param("despacho", despacho).param("operacion", operacion)
                .param("anterior", anterior).param("nuevo", nuevo).param("actor", actor).param("observacion", observacion)
                .param("datos", json.writeValueAsString(datos)).param("fecha", OffsetDateTime.ofInstant(ahora, ZoneOffset.UTC)).update();
        return id;
    }
}
