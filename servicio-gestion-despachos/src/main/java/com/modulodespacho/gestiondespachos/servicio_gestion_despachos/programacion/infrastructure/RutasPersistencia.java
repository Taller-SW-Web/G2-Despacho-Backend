package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ValidadorSecuencia;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.Despacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosRuta.*;

@Repository
@Profile("!integracion-local")
public class RutasPersistencia {
    private final JdbcClient sql;
    private final JsonMapper json;
    private final EntityManager entidades;
    private final ProgramacionPersistencia programacion;
    public RutasPersistencia(JdbcClient sql, JsonMapper json, EntityManager entidades, ProgramacionPersistencia programacion) {
        this.sql = sql; this.json = json; this.entidades = entidades; this.programacion = programacion;
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Ruta consultar(String jornada, String estado, Instant ahora) { return vista(jornada, estado, filas(jornada, false), ahora); }

    @Transactional(readOnly=true)
    public Optional<Ruta> recuperar(String actor, UUID clave, String solicitud) {
        return sql.sql("SELECT solicitud,respuesta::text FROM resultados_secuencia_ruta WHERE ejecutor_id=:actor AND clave=:clave")
                .param("actor", actor).param("clave", clave).query((r, i) -> {
                    if (!solicitud.equals(r.getString(1))) throw conflicto("La clave ya se utilizó con otro guardado");
                    return json.readValue(r.getString(2), Ruta.class);
                }).optional();
    }

    @Transactional
    public Ruta guardar(String jornada, String estado, String version, Secuencia solicitud, String actor, UUID clave, Instant ahora) {
        bloquear("secuencia-clave:" + actor + ":" + clave);
        String huella = solicitud(jornada, version, solicitud);
        var previa = recuperar(actor, clave, huella);
        if (previa.isPresent()) return previa.get();
        if (!"ACTIVA".equals(estado)) throw conflicto("La jornada no está activa");
        bloquear("secuencia-jornada:" + jornada);
        var actuales = filas(jornada, true);
        if (!version(actuales).equals(version)) throw conflicto("La ruta cambió. Actualiza antes de guardar");
        var nuevas = ValidadorSecuencia.validar(actuales, solicitud);
        for (var parada : actuales) {
            int posicion = nuevas.get(parada.idDespacho());
            if (posicion == parada.posicion()) continue;
            GuardiaReasignacion.exigirLibre(sql,parada.idDespacho());
            int cambios = sql.sql("UPDATE despachos SET secuencia_ruta=:posicion,version=version+1,actualizado_en=:ahora WHERE id=:id AND version=:version AND estado='ASIGNADO'")
                    .param("posicion", posicion).param("ahora", ahora.atOffset(ZoneOffset.UTC)).param("id", parada.idDespacho()).param("version", parada.version()).update();
            if (cambios != 1) throw conflicto("El despacho cambió mientras se guardaba");
            sql.sql("""
                    INSERT INTO historial_estados_despacho(despacho_id,tipo_operacion,estado_anterior,estado_nuevo,
                        funcionalidad_origen,ejecutor_tipo,ejecutor_id,origen,observaciones,datos,registrado_en)
                    VALUES (:id,'REORDENAMIENTO','ASIGNADO','ASIGNADO','F-02','USUARIO',:actor,'MANUAL',
                        'Reordenamiento de ruta',CAST(:datos AS jsonb),:ahora)
                    """).param("id", parada.idDespacho()).param("actor", actor).param("ahora", ahora.atOffset(ZoneOffset.UTC))
                    .param("datos", json.writeValueAsString(Map.of("idJornada", jornada, "posicionAnterior", parada.posicion(),
                            "posicionNueva", posicion, "secuenciaAnterior", actuales.stream().map(p -> new Posicion(p.idDespacho(), p.posicion())).toList(),
                            "secuenciaNueva", solicitud.despachos()))).update();
            programacion.publicarProyeccion(entidades.find(Despacho.class, parada.idDespacho()), ahora);
        }
        Ruta respuesta = vista(jornada, estado, filas(jornada, false), ahora);
        sql.sql("INSERT INTO resultados_secuencia_ruta(ejecutor_id,clave,solicitud,respuesta) VALUES (:actor,:clave,:solicitud,CAST(:respuesta AS jsonb))")
                .param("actor", actor).param("clave", clave).param("solicitud", huella).param("respuesta", json.writeValueAsString(respuesta)).update();
        return respuesta;
    }

    public String solicitud(String jornada, String version, Secuencia secuencia) {
        return json.writeValueAsString(List.of(jornada, version, secuencia));
    }
    private List<Parada> filas(String jornada, boolean bloquear) {
        return sql.sql("SELECT d.*,EXISTS(SELECT 1 FROM reasignaciones_despacho r WHERE r.despacho_id=d.id AND r.estado='PENDIENTE') AS reasignacion_en_proceso,"
                + "EXISTS(SELECT 1 FROM cancelaciones_despacho c WHERE c.despacho_id=d.id AND c.estado='PENDIENTE') AS cancelacion_en_proceso "
                + "FROM despachos d WHERE asignacion_diaria_id_externa=:jornada AND secuencia_ruta IS NOT NULL AND d.estado<>'CANCELADO' ORDER BY id" + (bloquear ? " FOR UPDATE OF d" : ""))
                .param("jornada", jornada).query((r, i) -> new Parada(r.getObject("id", UUID.class), r.getString("codigo_operativo"),
                        r.getString("codigo_rastreo_interno"), r.getString("estado"), r.getInt("secuencia_ruta"), r.getInt("version"),
                        r.getString("destinatario_nombre"), r.getString("direccion"), r.getBigDecimal("peso_kg"), r.getBigDecimal("volumen_m3"),
                        r.getInt("cantidad_paquetes"), r.getObject("actualizado_en", OffsetDateTime.class).toInstant(),r.getString("pedido_id"),
                        r.getString("repartidor_id_externo"),r.getBoolean("reasignacion_en_proceso"),r.getBoolean("cancelacion_en_proceso"))).list();
    }
    private Ruta vista(String jornada, String estado, List<Parada> filas, Instant ahora) {
        return new Ruta(jornada, estado, filas.stream().sorted(Comparator.comparingInt(Parada::posicion)).toList(), version(filas), ahora);
    }
    private String version(List<Parada> filas) {
        try {
            String contenido = json.writeValueAsString(filas.stream().sorted(Comparator.comparing(Parada::idDespacho))
                    .map(p -> List.of(p.idDespacho(), p.version(), p.posicion(), p.estado(),p.reasignacionEnProceso(),p.cancelacionEnProceso())).toList());
            return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido.getBytes(StandardCharsets.UTF_8))) + "\"";
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private void bloquear(String clave) { sql.sql("SELECT pg_advisory_xact_lock(hashtextextended(:clave,0))").param("clave", clave).query((r,i) -> true).single(); }
    private static ErrorDeNegocio conflicto(String mensaje) { return ErrorDeNegocio.de(409, "DESP_ERROR_RUTA_DESACTUALIZADA", mensaje); }
}
