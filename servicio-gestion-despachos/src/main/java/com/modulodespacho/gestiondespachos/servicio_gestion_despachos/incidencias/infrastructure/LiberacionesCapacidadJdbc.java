package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LiberacionesPendientes;

@Repository
@Profile("!integracion-local")
public class LiberacionesCapacidadJdbc implements LiberacionesPendientes {
    private final JdbcClient sql;
    public LiberacionesCapacidadJdbc(JdbcClient sql) { this.sql = sql; }
    @Override public Optional<Envio> reclamar(Instant ahora) {
        UUID token = UUID.randomUUID();
        return sql.sql("""
                WITH siguiente AS (
                  SELECT id FROM liberaciones_capacidad WHERE estado = 'PENDIENTE' AND proximo_intento_en <= :ahora
                    AND (bloqueada_hasta IS NULL OR bloqueada_hasta <= :ahora)
                  ORDER BY proximo_intento_en, creado_en, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE liberaciones_capacidad l SET token_procesamiento = :token, bloqueada_hasta = :vence,
                  intentos_envio = intentos_envio + 1, ultimo_intento_en = :ahora, actualizado_en = :ahora
                FROM siguiente s WHERE l.id = s.id RETURNING l.*,
                  EXISTS (SELECT 1 FROM intentos_entrega i
                    WHERE i.id = l.incidencia_id AND i.despacho_id = l.despacho_id
                      AND i.resultado = 'FALLIDO' AND i.recibido_en_centro_en IS NOT NULL
                      AND i.recibido_por::text = l.solicitada_por AND i.sello_intacto IS NOT NULL
                      AND i.reserva_capacidad_id_externa = l.reserva_id_externa
                      AND i.asignacion_diaria_id_externa = l.jornada_id_externa
                      AND length(btrim(i.repartidor_id_externo)) > 0
                      AND l.hash_contenido ~ '^[0-9a-f]{64}$'
                      AND l.respuesta_original->>'idDespacho' = l.despacho_id::text
                      AND l.respuesta_original->>'idIncidencia' = l.incidencia_id::text
                      AND l.respuesta_original->'versionDespacho' = to_jsonb(l.version_solicitada::bigint + 1)
                      AND l.respuesta_original->>'estado' = 'FALLIDO'
                      AND l.respuesta_original#>>'{recepcion,idUsuario}' = l.solicitada_por
                      AND l.respuesta_original#>>'{recepcion,estado}' = 'RECIBIDO_EN_CENTRO'
                      AND l.respuesta_original#>'{recepcion,selloIntacto}' = to_jsonb(i.sello_intacto)
                      AND l.respuesta_original#>'{recepcion,observacion}' = coalesce(to_jsonb(i.observacion_recepcion), 'null'::jsonb)
                      AND l.respuesta_original#>>'{recepcion,liberacionCapacidad,idSolicitud}' = l.id::text
                      AND l.respuesta_original#>>'{recepcion,liberacionCapacidad,estado}' = 'PENDIENTE'
                  ) AS contexto_valido
                """).param("token", token).param("ahora", fecha(ahora)).param("vence", fecha(ahora.plusSeconds(60)))
                .query((f, n) -> new Envio(f.getObject("id", UUID.class), token, f.getString("despacho_id"),
                        f.getString("incidencia_id"), f.getString("reserva_id_externa"),
                        f.getString("jornada_id_externa"), f.getInt("intentos_envio"), f.getBoolean("requiere_revision"),
                        f.getBoolean("contexto_valido"))).optional();
    }
    @Override public void confirmar(Envio envio, Instant ahora, Instant liberadaEn) {
        sql.sql("""
                UPDATE liberaciones_capacidad SET estado = 'CONFIRMADA', requiere_revision = false,
                  confirmada_en = :liberada, actualizado_en = :ahora, proximo_intento_en = NULL,
                  bloqueada_hasta = NULL, token_procesamiento = NULL, ultimo_codigo_error = NULL
                WHERE id = :id AND estado = 'PENDIENTE' AND token_procesamiento = :token AND bloqueada_hasta > :ahora
                """).param("liberada", fecha(liberadaEn)).param("ahora", fecha(ahora))
                .param("id", envio.id()).param("token", envio.token()).update();
    }
    @Override public void reintentar(Envio envio, Instant ahora, Instant siguiente, boolean revision, String codigo) {
        sql.sql("""
                UPDATE liberaciones_capacidad SET requiere_revision = requiere_revision OR :revision,
                  proximo_intento_en = :siguiente, actualizado_en = :ahora, ultimo_codigo_error = :codigo,
                  bloqueada_hasta = NULL, token_procesamiento = NULL
                WHERE id = :id AND estado = 'PENDIENTE' AND token_procesamiento = :token AND bloqueada_hasta > :ahora
                """).param("revision", revision).param("siguiente", fecha(siguiente)).param("ahora", fecha(ahora))
                .param("codigo", codigo).param("id", envio.id()).param("token", envio.token()).update();
    }
    private static OffsetDateTime fecha(Instant instante) { return OffsetDateTime.ofInstant(instante, ZoneOffset.UTC); }
}
