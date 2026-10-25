package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.DetalleIncidencia.*;

@Repository
@Profile("!integracion-local")
public class RecepcionCentroJdbc {
    private final JdbcClient sql;
    private final JsonMapper json;
    private final Clock reloj;
    private final TransactionTemplate transaccion;
    public RecepcionCentroJdbc(JdbcClient sql, JsonMapper json, Clock reloj, PlatformTransactionManager gestor) {
        this.sql = sql; this.json = json; this.reloj = reloj; this.transaccion = new TransactionTemplate(gestor);
    }
    public ResultadoRecepcion confirmar(String despacho, SolicitudRecepcion solicitud, String actor) {
        return transaccion.execute(estado -> registrar(despacho, solicitud, actor));
    }
    private ResultadoRecepcion registrar(String despacho, SolicitudRecepcion solicitud, String actor) {
        // Un único orden de bloqueo serializa las recepciones sobre este despacho.
        var actual = sql.sql("SELECT estado, version, ultima_incidencia_id::text AS incidencia FROM despachos WHERE id::text = :id FOR UPDATE")
                .param("id", despacho).query((f, n) -> new Actual(f.getString("estado"), f.getInt("version"), f.getString("incidencia")))
                .optional().orElseThrow(() -> ErrorDeNegocio.de(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe"));
        String huella = huella(json.writeValueAsString(solicitud));
        var anterior = sql.sql("""
                SELECT solicitada_por, hash_contenido, respuesta_original::text, id::text FROM liberaciones_capacidad
                WHERE despacho_id::text = :despacho AND incidencia_id::text = :incidencia AND version_solicitada = :version
                """).param("despacho", despacho).param("incidencia", solicitud.idIncidencia()).param("version", solicitud.versionDespacho())
                .query((f, n) -> new Aceptada(f.getString(1), f.getString(2), f.getString(3), f.getString(4))).optional();
        if (anterior.isPresent()) {
            var aceptada = anterior.get();
            if (!Objects.equals(actor, aceptada.actor()) || !huella.equals(aceptada.huella()))
                throw conflicto("DESP_ERROR_OPERACION_REUTILIZADA", "La operación ya fue aceptada con otro contenido o ejecutor");
            return recuperar(aceptada, despacho, solicitud, actor);
        }
        if (actual.version() != solicitud.versionDespacho() || !Objects.equals(actual.incidencia(), solicitud.idIncidencia()))
            throw conflicto("DESP_ERROR_DESPACHO_DESACTUALIZADO", "La incidencia o versión cambió; vuelve a consultar");
        if (!"FALLIDO".equals(actual.estado())) throw conflicto("DESP_ERROR_ESTADO_INCOMPATIBLE", "El despacho no está fallido");
        var salida = sql.sql("""
                SELECT reserva_capacidad_id_externa, asignacion_diaria_id_externa, repartidor_id_externo,
                  recibido_en_centro_en, recibido_por, sello_intacto, observacion_recepcion
                FROM intentos_entrega WHERE id::text = :incidencia AND despacho_id::text = :despacho AND resultado = 'FALLIDO'
                FOR UPDATE
                """).param("incidencia", solicitud.idIncidencia()).param("despacho", despacho)
                .query((f, n) -> new Salida(f.getString(1), f.getString(2), f.getString(3), f.getObject(4) != null,
                        f.getObject(5) != null || f.getObject(6) != null || f.getObject(7) != null))
                .optional().orElseThrow(RecepcionCentroJdbc::inconsistencia);
        if (salida.recibida()) throw conflicto("DESP_ERROR_RECEPCION_YA_CONFIRMADA", "La recepción ya está confirmada");
        if (salida.parcial() || vacio(salida.reserva()) || vacio(salida.jornada()) || vacio(salida.repartidor())
                || sql.sql("SELECT count(*) FROM liberaciones_capacidad WHERE incidencia_id::text = :incidencia OR reserva_id_externa = :reserva")
                .param("incidencia", solicitud.idIncidencia()).param("reserva", salida.reserva()).query(Integer.class).single() != 0)
            throw inconsistencia();
        Instant ahora = reloj.instant();
        var fecha = OffsetDateTime.ofInstant(ahora, ZoneOffset.UTC);
        var idSolicitud = UUID.randomUUID();
        var recepcion = new Recepcion("RECIBIDO_EN_CENTRO", ahora, actor, solicitud.selloIntacto(), solicitud.observacion(),
                new LiberacionCapacidad(idSolicitud.toString(), "PENDIENTE", false, ahora, null));
        var respuesta = new ResultadoRecepcion(despacho, solicitud.idIncidencia(), "FALLIDO", actual.version() + 1, recepcion);
        sql.sql("""
                UPDATE intentos_entrega SET recibido_en_centro_en = :fecha, recibido_por = CAST(:actor AS uuid),
                  sello_intacto = :sello, observacion_recepcion = :observacion, actualizado_en = :fecha WHERE id::text = :id
                """).param("fecha", fecha).param("actor", actor).param("sello", solicitud.selloIntacto())
                .param("observacion", solicitud.observacion()).param("id", solicitud.idIncidencia()).update();
        sql.sql("UPDATE despachos SET version = version + 1, actualizado_en = :fecha WHERE id::text = :id")
                .param("fecha", fecha).param("id", despacho).update();
        var datos = new LinkedHashMap<String, Object>();
        datos.put("idIncidencia", solicitud.idIncidencia()); datos.put("idReserva", salida.reserva());
        datos.put("selloIntacto", solicitud.selloIntacto()); datos.put("observacionRecepcion", solicitud.observacion());
        sql.sql("""
                INSERT INTO historial_estados_despacho (id, despacho_id, tipo_operacion, estado_anterior, estado_nuevo,
                  funcionalidad_origen, ejecutor_tipo, ejecutor_id, origen, observaciones, datos, registrado_en)
                VALUES (:id, CAST(:despacho AS uuid), 'RECEPCION_EN_CENTRO', 'FALLIDO', 'FALLIDO',
                  'F-04', 'USUARIO', :actor, 'MANUAL', :observacion, CAST(:datos AS jsonb), :fecha)
                """).param("id", UUID.randomUUID()).param("despacho", despacho).param("actor", actor)
                .param("observacion", solicitud.observacion()).param("datos", json.writeValueAsString(datos)).param("fecha", fecha).update();
        sql.sql("""
                INSERT INTO liberaciones_capacidad (id, despacho_id, incidencia_id, reserva_id_externa, jornada_id_externa,
                  solicitada_por, version_solicitada, hash_contenido, respuesta_original, estado, proximo_intento_en, creado_en, actualizado_en)
                VALUES (:id, CAST(:despacho AS uuid), CAST(:incidencia AS uuid), :reserva, :jornada,
                  :actor, :version, :huella, CAST(:respuesta AS jsonb), 'PENDIENTE', :fecha, :fecha, :fecha)
                """).param("id", idSolicitud).param("despacho", despacho).param("incidencia", solicitud.idIncidencia())
                .param("reserva", salida.reserva()).param("jornada", salida.jornada()).param("actor", actor)
                .param("version", solicitud.versionDespacho()).param("huella", huella)
                .param("respuesta", json.writeValueAsString(respuesta)).param("fecha", fecha).update();
        return respuesta;
    }
    private static boolean vacio(String dato) { return dato == null || dato.isBlank(); }
    private ResultadoRecepcion recuperar(Aceptada aceptada, String despacho, SolicitudRecepcion solicitud, String actor) {
        ResultadoRecepcion original;
        try { original = json.readValue(aceptada.respuesta(), ResultadoRecepcion.class); }
        catch (RuntimeException error) { throw inconsistencia(); }
        if (original == null || !despacho.equals(original.idDespacho()) || !solicitud.idIncidencia().equals(original.idIncidencia())
                || !"FALLIDO".equals(original.estado()) || original.versionDespacho() != solicitud.versionDespacho() + 1)
            throw inconsistencia();
        var recepcion = original.recepcion();
        if (recepcion == null || !"RECIBIDO_EN_CENTRO".equals(recepcion.estado()) || recepcion.confirmadaEn() == null
                || !actor.equals(recepcion.idUsuario()) || !Objects.equals(recepcion.selloIntacto(), solicitud.selloIntacto())
                || !Objects.equals(recepcion.observacion(), solicitud.observacion())) throw inconsistencia();
        var liberacion = recepcion.liberacionCapacidad();
        if (liberacion == null || !aceptada.id().equals(liberacion.idSolicitud()) || !"PENDIENTE".equals(liberacion.estado())
                || liberacion.requiereRevision() || liberacion.confirmadaEn() != null || liberacion.actualizadaEn() == null)
            throw inconsistencia();
        return original;
    }
    private static String huella(String contenido) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static ErrorDeNegocio conflicto(String codigo, String mensaje) { return ErrorDeNegocio.de(409, codigo, mensaje); }
    private static ErrorDeNegocio inconsistencia() {
        return ErrorDeNegocio.de(500, "DESP_ERROR_DATOS_INCONSISTENTES", "La incidencia no conserva referencias locales coherentes");
    }
    private record Actual(String estado, int version, String incidencia) {}
    private record Aceptada(String actor, String huella, String respuesta, String id) {}
    private record Salida(String reserva, String jornada, String repartidor, boolean recibida, boolean parcial) {}
}
