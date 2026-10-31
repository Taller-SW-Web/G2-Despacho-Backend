package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.time.*;
import java.util.*;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.AlmacenCancelaciones;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.Despacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosCancelacion.*;

@Repository
@Profile("!integracion-local")
public class CancelacionesPersistencia implements AlmacenCancelaciones {
    private final JdbcClient sql;
    private final JsonMapper json;
    private final EntityManager entidades;
    private final ProgramacionPersistencia programacion;
    public CancelacionesPersistencia(JdbcClient sql, JsonMapper json, EntityManager entidades, ProgramacionPersistencia programacion) {
        this.sql = sql; this.json = json; this.entidades = entidades; this.programacion = programacion;
    }
    @Override @Transactional
    public Intencion preparar(String pedido, UUID clave, Solicitud solicitud, String actor, Instant ahora) {
        bloquear("cancelacion-clave:" + actor + ":" + clave);
        String huella = json.writeValueAsString(List.of(pedido, solicitud));
        var repetida = sql.sql("SELECT solicitud,intencion::text FROM cancelaciones_despacho WHERE ejecutor_id=:actor AND clave=:clave")
                .param("actor", actor).param("clave", clave).query((r, i) -> {
                    if (!huella.equals(r.getString(1))) throw conflicto("DESP_ERROR_OPERACION_REUTILIZADA", "La clave identifica otra cancelación");
                    return json.readValue(r.getString(2), Intencion.class);
                }).optional();
        if (repetida.isPresent()) return repetida.get();
        UUID id = sql.sql("SELECT id FROM despachos WHERE pedido_id=:pedido").param("pedido", pedido).query(UUID.class).optional()
                .orElseThrow(() -> ErrorDeNegocio.de(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "No existe un despacho para el pedido"));
        bloquear("asignacion:" + id);
        var actual = actual(id);
        GuardiaReasignacion.exigirLibre(sql, id);
        if (sql.sql("SELECT EXISTS(SELECT 1 FROM asignaciones_despacho WHERE despacho_id=:id AND estado IN ('PENDIENTE','COMPENSACION_PENDIENTE'))")
                .param("id", id).query(Boolean.class).single())
            throw conflicto("DESP_ERROR_ASIGNACION_EN_PROCESO", "Primero debe resolverse la asignación en proceso");
        if (!Set.of("PENDIENTE_ASIGNACION", "ASIGNADO", "FALLIDO").contains(actual.estado()))
            throw conflicto("DESP_ERROR_ESTADO_INCOMPATIBLE", "El despacho no admite cancelación").conEstadoActual(actual.estado());
        if ("ASIGNADO".equals(actual.estado()) && (actual.reserva() == null || actual.jornada() == null))
            throw conflicto("DESP_ERROR_DATOS_INCONSISTENTES", "La asignación no tiene referencias operativas completas");
        var intencion = new Intencion(UUID.randomUUID(), id, pedido, clave, actor, actual.version(), actual.estado(),
                actual.reserva(), actual.jornada(), solicitud);
        sql.sql("INSERT INTO cancelaciones_despacho(id,despacho_id,ejecutor_id,clave,solicitud,intencion,estado) VALUES (:id,:despacho,:actor,:clave,:solicitud,CAST(:intencion AS jsonb),'PENDIENTE')")
                .param("id", intencion.id()).param("despacho", id).param("actor", actor).param("clave", clave).param("solicitud", huella)
                .param("intencion", json.writeValueAsString(intencion)).update();
        // Sin efecto remoto: intención, decisión y resultado se confirman en la misma transacción.
        if (!"ASIGNADO".equals(actual.estado())) confirmar(intencion, null, ahora);
        return intencion;
    }
    @Override @Transactional(readOnly=true)
    public Optional<Resultado> resultado(UUID id) {
        return sql.sql("SELECT resultado::text FROM cancelaciones_despacho WHERE id=:id AND estado='CONFIRMADA'")
                .param("id", id).query((r, i) -> json.readValue(r.getString(1), Resultado.class)).optional();
    }
    @Override @Transactional
    public boolean reclamar(UUID id) {
        return sql.sql("UPDATE cancelaciones_despacho SET procesando_hasta=now()+interval '60 seconds' WHERE id=:id AND estado='PENDIENTE' AND (procesando_hasta IS NULL OR procesando_hasta<now())")
                .param("id", id).update() == 1;
    }
    @Override @Transactional
    public void aplazar(UUID id) {
        sql.sql("UPDATE cancelaciones_despacho SET procesando_hasta=NULL,proximo_intento_en=now()+interval '30 seconds' WHERE id=:id AND estado='PENDIENTE'")
                .param("id", id).update();
    }
    @Override @Transactional
    public Resultado confirmar(Intencion intencion, Liberacion liberacion, Instant ahora) {
        bloquear("asignacion:" + intencion.idDespacho());
        sql.sql("SELECT id FROM cancelaciones_despacho WHERE id=:id FOR UPDATE").param("id", intencion.id()).query(UUID.class).single();
        var previa = resultado(intencion.id());
        if (previa.isPresent()) return previa.get();
        // Igual orden que reasignación/reordenamiento: jornada antes de fila en la confirmación remota.
        if ("ASIGNADO".equals(intencion.estadoOrigen())) bloquear("secuencia-jornada:" + intencion.idJornada());
        var actual = actual(intencion.idDespacho());
        if (actual.version() != intencion.versionOrigen() || !actual.estado().equals(intencion.estadoOrigen())
                || !Objects.equals(actual.reserva(), intencion.idReserva()) || !Objects.equals(actual.jornada(), intencion.idJornada()))
            throw conflicto("DESP_ERROR_DESPACHO_DESACTUALIZADO", "El despacho no coincide con la intención; requiere recuperación").conEstadoActual(actual.estado());
        if ("ASIGNADO".equals(actual.estado()) && (liberacion == null || !intencion.idReserva().equals(liberacion.idReserva())
                || !intencion.idDespacho().toString().equals(liberacion.idDespacho()) || !"LIBERADA".equals(liberacion.estado())
                || !"CANCELACION".equals(liberacion.motivoLiberacion()) || liberacion.liberadaEn() == null))
            throw conflicto("DESP_ERROR_DATOS_INCONSISTENTES", "La reserva no tiene una liberación confirmada por cancelación");
        boolean fallido = "FALLIDO".equals(actual.estado());
        String nuevo = fallido ? "FALLIDO" : "CANCELADO";
        Instant procesado = ahora.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (fallido && actual.anuladoEn() != null) {
            // Otra clave para la misma marca no duplica historial ni modifica su fecha.
            procesado = actual.anuladoEn();
        } else {
            int cambios = sql.sql("UPDATE despachos SET estado=:estado,pedido_anulado_en=coalesce(pedido_anulado_en,:ahora),version=version+1,actualizado_en=:ahora WHERE id=:id AND version=:version")
                    .param("estado", nuevo).param("ahora", procesado.atOffset(ZoneOffset.UTC)).param("id", intencion.idDespacho()).param("version", actual.version()).update();
            if (cambios != 1) throw conflicto("DESP_ERROR_DESPACHO_DESACTUALIZADO", "El despacho cambió durante la decisión");
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("idPedido", intencion.idPedido()); datos.put("motivo", intencion.solicitud().motivo());
            datos.put("pedidoAnulado", true); datos.put("idReserva", intencion.idReserva()); datos.put("idJornada", intencion.idJornada());
            if (liberacion != null) datos.put("liberacion", liberacion);
            sql.sql("""
                    INSERT INTO historial_estados_despacho(despacho_id,tipo_operacion,estado_anterior,estado_nuevo,funcionalidad_origen,
                        ejecutor_tipo,ejecutor_id,origen,motivo_codigo,observaciones,datos,registrado_en)
                    VALUES (:id,'CANCELACION_POR_ANULACION',:anterior,:nuevo,'F-02','SERVICIO',:actor,'AUTOMATICO',:motivo,:observacion,CAST(:datos AS jsonb),:ahora)
                    """).param("id", intencion.idDespacho()).param("anterior", actual.estado()).param("nuevo", nuevo).param("actor", intencion.ejecutor())
                    .param("motivo", intencion.solicitud().motivo()).param("observacion", intencion.solicitud().observacion())
                    .param("datos", json.writeValueAsString(datos)).param("ahora", procesado.atOffset(ZoneOffset.UTC)).update();
            if (!fallido) {
                Despacho despacho = entidades.find(Despacho.class, intencion.idDespacho());
                if (!actual.simulado()) {
                    UUID evento = UUID.randomUUID();
                    var contenido = Map.of("idEvento", evento, "tipo", "DESPACHO_CANCELADO", "idPedido", intencion.idPedido(),
                            "idDespacho", intencion.idDespacho(), "estadoAnterior", actual.estado(), "estadoNuevo", nuevo,
                            "ocurridoEn", procesado, "datos", Map.of("motivo", intencion.solicitud().motivo()));
                    sql.sql("INSERT INTO eventos_salientes(id,despacho_id,destino,tipo_evento,payload) VALUES (:id,:despacho,'VENTAS','DESPACHO_CANCELADO',CAST(:payload AS jsonb))")
                            .param("id", evento).param("despacho", intencion.idDespacho()).param("payload", json.writeValueAsString(contenido)).update();
                }
                if ("ASIGNADO".equals(actual.estado())) programacion.publicarProyeccion(despacho, procesado);
            }
        }
        var resultado = new Resultado(fallido ? 202 : 200, new Respuesta(intencion.idPedido(), intencion.idDespacho().toString(), nuevo, procesado));
        sql.sql("UPDATE cancelaciones_despacho SET estado='CONFIRMADA',resultado=CAST(:resultado AS jsonb),procesando_hasta=NULL WHERE id=:id")
                .param("id", intencion.id()).param("resultado", json.writeValueAsString(resultado)).update();
        return resultado;
    }
    @Override @Transactional(readOnly=true)
    public List<Intencion> recuperables() {
        return sql.sql("SELECT intencion::text FROM cancelaciones_despacho WHERE estado='PENDIENTE' AND proximo_intento_en<=now() AND (procesando_hasta IS NULL OR procesando_hasta<now()) ORDER BY creado_en,id LIMIT 20")
                .query((r, i) -> json.readValue(r.getString(1), Intencion.class)).list();
    }
    private Actual actual(UUID id) {
        return sql.sql("SELECT estado,version,reserva_capacidad_id_externa,asignacion_diaria_id_externa,es_simulado,pedido_anulado_en FROM despachos WHERE id=:id FOR UPDATE")
                .param("id", id).query((r, i) -> new Actual(r.getString(1), r.getInt(2), r.getString(3), r.getString(4), r.getBoolean(5),
                        r.getObject(6) == null ? null : r.getObject(6, OffsetDateTime.class).toInstant())).single();
    }
    private record Actual(String estado, int version, String reserva, String jornada, boolean simulado, Instant anuladoEn) {}
    private void bloquear(String clave) { sql.sql("SELECT pg_advisory_xact_lock(hashtextextended(:clave,0))").param("clave", clave).query((r, i) -> true).single(); }
    private static ErrorDeNegocio conflicto(String codigo, String mensaje) { return ErrorDeNegocio.de(409, codigo, mensaje); }
}
