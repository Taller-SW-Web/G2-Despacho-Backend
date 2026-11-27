package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.AlmacenAdelantos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosAdelanto.Respuesta;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.Despacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.EstadoDespacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Cambio local de programación: no publica progreso ni invoca Operación. */
@Repository
@Profile("!integracion-local")
public class AdelantosPersistencia implements AlmacenAdelantos {
    private final JdbcClient sql;
    private final EntityManager entidades;
    private final JsonMapper json;

    public AdelantosPersistencia(JdbcClient sql, EntityManager entidades, JsonMapper json) {
        this.sql = sql;
        this.entidades = entidades;
        this.json = json;
    }

    @Override
    @Transactional
    public Respuesta adelantar(
            UUID id,
            String clave,
            int version,
            String motivo,
            String actor,
            LocalDate hoy,
            Instant ahora) {
        bloquear("adelanto-clave:" + actor + ":" + clave);
        // Representación canónica completa, sin incluir el día del reintento.
        String huella = json.writeValueAsString(java.util.List.of(id, version, motivo));
        var repetida =
                sql.sql(
                                """
                SELECT huella_solicitud, respuesta::text FROM claves_idempotencia
                WHERE actor_tipo='USUARIO' AND actor_id=:actor AND operacion='ADELANTAR_DESPACHO' AND clave=:clave
                """)
                        .param("actor", actor)
                        .param("clave", clave)
                        .query(
                                (fila, indice) -> {
                                    if (!huella.equals(fila.getString(1)))
                                        throw conflicto(
                                                "DESP_ERROR_OPERACION_REUTILIZADA",
                                                "La clave corresponde a otro adelanto");
                                    return json.readValue(fila.getString(2), Respuesta.class);
                                })
                        .optional();
        if (repetida.isPresent()) return repetida.get();

        // Mismo cerco que asignación y cancelación; nunca mantener HTTP dentro de esta transacción.
        bloquear("asignacion:" + id);
        Despacho despacho = entidades.find(Despacho.class, id);
        if (despacho == null)
            throw ErrorDeNegocio.de(
                    404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe");
        if (despacho.getVersion() != version)
            throw conflicto(
                    "DESP_ERROR_DESPACHO_DESACTUALIZADO",
                    "El despacho cambió; vuelve a consultar la cola");
        if (despacho.getEstado() != EstadoDespacho.PENDIENTE_ASIGNACION)
            throw conflicto(
                    "DESP_ERROR_ESTADO_INCOMPATIBLE", "Solo se adelantan despachos pendientes");
        if (sql.sql(
                        """
                SELECT EXISTS(SELECT 1 FROM asignaciones_despacho WHERE despacho_id=:id
                    AND estado IN ('PENDIENTE','COMPENSACION_PENDIENTE'))
                  OR EXISTS(SELECT 1 FROM cancelaciones_despacho WHERE despacho_id=:id AND estado='PENDIENTE')
                """)
                .param("id", id)
                .query(Boolean.class)
                .single())
            throw conflicto(
                    "DESP_ERROR_OPERACION_EN_PROCESO", "El despacho tiene una operación pendiente");
        LocalDate anterior = despacho.getFechaProgramada();
        if (anterior == null)
            throw ErrorDeNegocio.de(
                    500,
                    "DESP_ERROR_DATOS_INCONSISTENTES",
                    "El despacho no tiene fecha programada");
        if (!anterior.isAfter(hoy))
            throw conflicto(
                    "DESP_ERROR_ADELANTO_NO_APLICABLE",
                    "El despacho ya está disponible para asignación");
        despacho.setFechaProgramada(hoy);
        despacho.setActualizadoEn(ahora);
        entidades.flush();
        var respuesta =
                new Respuesta(
                        id,
                        despacho.getEstado().name(),
                        anterior,
                        hoy,
                        despacho.getVersion(),
                        motivo,
                        ahora);
        sql.sql(
                        """
                INSERT INTO historial_estados_despacho(despacho_id,tipo_operacion,estado_anterior,estado_nuevo,
                    funcionalidad_origen,ejecutor_tipo,ejecutor_id,origen,observaciones,datos,registrado_en)
                VALUES (:id,'ADELANTO_PROGRAMACION','PENDIENTE_ASIGNACION','PENDIENTE_ASIGNACION',
                    'F-02','USUARIO',:actor,'MANUAL',:motivo,CAST(:datos AS jsonb),:ahora)
                """)
                .param("id", id)
                .param("actor", actor)
                .param("motivo", motivo)
                .param(
                        "datos",
                        json.writeValueAsString(
                                Map.of(
                                        "fechaProgramadaAnterior",
                                        anterior,
                                        "fechaProgramada",
                                        hoy)))
                .param("ahora", ahora.atOffset(ZoneOffset.UTC))
                .update();
        sql.sql(
                        """
                INSERT INTO claves_idempotencia(clave,actor_tipo,actor_id,operacion,huella_solicitud,estado,codigo_respuesta,respuesta)
                VALUES (:clave,'USUARIO',:actor,'ADELANTAR_DESPACHO',:huella,'COMPLETADA',200,CAST(:respuesta AS jsonb))
                """)
                .param("clave", clave)
                .param("actor", actor)
                .param("huella", huella)
                .param("respuesta", json.writeValueAsString(respuesta))
                .update();
        return respuesta;
    }

    private void bloquear(String clave) {
        sql.sql("SELECT pg_advisory_xact_lock(hashtextextended(:clave,0))")
                .param("clave", clave)
                .query((fila, indice) -> true)
                .single();
    }

    private static ErrorDeNegocio conflicto(String codigo, String mensaje) {
        return ErrorDeNegocio.de(409, codigo, mensaje);
    }
}
