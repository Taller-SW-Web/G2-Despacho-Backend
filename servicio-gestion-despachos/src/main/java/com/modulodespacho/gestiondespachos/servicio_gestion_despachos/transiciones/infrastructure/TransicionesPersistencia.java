package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.transiciones.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.Destinatario;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.Destino;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.Despacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.EstadoDespacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.transiciones.application.AlmacenTransiciones;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.transiciones.application.ModelosTransicion.*;

@Repository
@Profile("!integracion-local")
public class TransicionesPersistencia implements AlmacenTransiciones {
    private static final String OPERACION = "TRANSICION_DESPACHO";
    private final JdbcClient sql;
    private final EntityManager entidades;
    private final JsonMapper json;
    public TransicionesPersistencia(JdbcClient sql, EntityManager entidades, JsonMapper json) {
        this.sql = sql; this.entidades = entidades; this.json = json;
    }

    @Override
    @Transactional
    public RespuestaTransicion registrar(UUID id, String clave, SolicitudTransicion s, String actor, Instant ahora) {
        String huella = huella(id, s);
        bloquear("transicion-clave:" + actor + ":" + clave);
        var repetida = sql.sql("""
                SELECT huella_solicitud, respuesta::text FROM claves_idempotencia
                WHERE actor_tipo = 'SERVICIO' AND actor_id = :actor AND operacion = :operacion AND clave = :clave
                """).param("actor", actor).param("operacion", OPERACION).param("clave", clave).query((fila, indice) -> {
                    if (!huella.equals(fila.getString(1))) throw conflicto("DESP_ERROR_OPERACION_REUTILIZADA", "La clave ya fue utilizada con otra solicitud");
                    return json.readValue(fila.getString(2), RespuestaTransicion.class);
                }).optional();
        if (repetida.isPresent()) return repetida.get();
        // Misma clave de bloqueo que F-02: una asignación y una transición nunca se cruzan.
        bloquear("asignacion:" + id);
        Despacho despacho = entidades.find(Despacho.class, id);
        if (despacho == null) throw ErrorDeNegocio.de(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe");
        EstadoDespacho anterior = despacho.getEstado();
        if (!Objects.equals(despacho.getIdRepartidor(), s.idRepartidor()) || !Objects.equals(despacho.getIdJornada(), s.idJornada()))
            throw conflicto("DESP_ERROR_DESPACHO_REASIGNADO", "El despacho ya no pertenece a esta ruta").conEstadoActual(anterior.name());
        boolean noIntentado = "NO_INTENTADO".equals(s.motivo());
        boolean origenValido = switch (s.estadoDestino()) {
            case "EN_CAMINO" -> anterior == EstadoDespacho.ASIGNADO;
            case "ENTREGADO" -> anterior == EstadoDespacho.EN_CAMINO;
            default -> noIntentado ? anterior == EstadoDespacho.ASIGNADO || anterior == EstadoDespacho.EN_CAMINO
                    : anterior == EstadoDespacho.EN_CAMINO;
        };
        if (!origenValido) throw conflicto("DESP_ERROR_ESTADO_INCOMPATIBLE", "El despacho ya no admite esta acción").conEstadoActual(anterior.name());
        boolean consumeIntento = false;
        if ("FALLIDO".equals(s.estadoDestino())) consumeIntento = validarMotivo(s.motivo(), noIntentado);

        UUID intento = null;
        if (!"EN_CAMINO".equals(s.estadoDestino())) {
            intento = UUID.randomUUID();
            sql.sql("""
                    INSERT INTO intentos_entrega(id, despacho_id, asignacion_diaria_id_externa, repartidor_id_externo,
                        reserva_capacidad_id_externa, resultado, motivo_codigo, consume_intento, comentario, nombre_receptor,
                        evidencia_id_externa, origen, registrado_en)
                    VALUES (:id, :despacho, :jornada, :repartidor, :reserva, :resultado, :motivo, :consume, :comentario,
                        :receptor, CAST(:evidencia AS uuid), :origen, :ahora)
                    """).param("id", intento).param("despacho", id).param("jornada", despacho.getIdJornada())
                    .param("repartidor", despacho.getIdRepartidor()).param("reserva", despacho.getIdReserva())
                    .param("resultado", s.estadoDestino()).param("motivo", s.motivo()).param("consume", consumeIntento)
                    .param("comentario", s.comentario()).param("receptor", s.recibidoPor()).param("evidencia", s.idEvidencia())
                    .param("origen", s.origen()).param("ahora", ahora.atOffset(ZoneOffset.UTC)).update();
        }
        despacho.setEstado(EstadoDespacho.valueOf(s.estadoDestino()));
        if (consumeIntento) despacho.setNumeroIntentos(despacho.getNumeroIntentos() + 1);
        despacho.setActualizadoEn(ahora);
        entidades.flush(); // @Version agrega la comparación de versión a la escritura.
        if ("FALLIDO".equals(s.estadoDestino())) {
            sql.sql("UPDATE despachos SET ultima_incidencia_id = :intento WHERE id = :id").param("intento", intento).param("id", id).update();
        }
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("idRepartidor", s.idRepartidor()); datos.put("idJornada", s.idJornada());
        if (s.idEvidencia() != null) datos.put("idEvidencia", s.idEvidencia());
        if (s.recibidoPor() != null) datos.put("recibidoPor", s.recibidoPor());
        if (intento != null) datos.put("idIntento", intento.toString());
        if ("FALLIDO".equals(s.estadoDestino())) { datos.put("consumeIntento", consumeIntento); datos.put("numeroIntentos", despacho.getNumeroIntentos()); }
        sql.sql("""
                INSERT INTO historial_estados_despacho(despacho_id, tipo_operacion, estado_anterior, estado_nuevo, funcionalidad_origen,
                    ejecutor_tipo, ejecutor_id, origen, motivo_codigo, observaciones, datos, registrado_en)
                VALUES (:id, 'TRANSICION', :anterior, :nuevo, 'F-03', :tipo, :ejecutor, :origen, :motivo, :observacion, CAST(:datos AS jsonb), :ahora)
                """).param("id", id).param("anterior", anterior.name()).param("nuevo", s.estadoDestino())
                .param("tipo", "AUTOMATICO".equals(s.origen()) ? "SISTEMA" : "USUARIO").param("ejecutor", s.ejecutor())
                .param("origen", s.origen()).param("motivo", s.motivo()).param("observacion", s.comentario())
                .param("datos", json.writeValueAsString(datos)).param("ahora", ahora.atOffset(ZoneOffset.UTC)).update();

        Hitos hitos = hitos(id);
        if (!despacho.isEsSimulacion()) eventoVentas(despacho, anterior, s, ahora);
        guardarEvento(id, "OPERACION", "PROYECCION_DESPACHO", proyeccion(despacho, s.motivo(), hitos, ahora));
        // Operación §5.2/§5.3: la proyección terminal no libera capacidad; Gestión la solicita de forma durable.
        // Un FALLIDO conserva la reserva hasta la recepción en centro (F-04).
        if ("ENTREGADO".equals(s.estadoDestino()) && despacho.getIdReserva() != null) {
            guardarEvento(id, "OPERACION_LIBERACION", "LIBERACION_CAPACIDAD",
                    Map.of("idReserva", despacho.getIdReserva(), "idDespacho", id.toString(), "motivo", "ENTREGA"));
        }
        var respuesta = new RespuestaTransicion(id.toString(), anterior.name(), s.estadoDestino(), despacho.getVersion(),
                despacho.getNumeroIntentos(), s.motivo(), hitos, ahora);
        sql.sql("""
                INSERT INTO claves_idempotencia(clave, actor_tipo, actor_id, operacion, huella_solicitud, estado, codigo_respuesta, respuesta)
                VALUES (:clave, 'SERVICIO', :actor, :operacion, :huella, 'COMPLETADA', 200, CAST(:respuesta AS jsonb))
                """).param("clave", clave).param("actor", actor).param("operacion", OPERACION).param("huella", huella)
                .param("respuesta", json.writeValueAsString(respuesta)).update();
        return respuesta;
    }

    private boolean validarMotivo(String motivo, boolean noIntentado) {
        var fila = sql.sql("SELECT seleccionable_en_campo, consume_intento, activo FROM motivos_fallo WHERE codigo = :codigo")
                .param("codigo", motivo).query((r, i) -> new boolean[] {r.getBoolean(1), r.getBoolean(2), r.getBoolean(3)}).optional()
                .orElseThrow(() -> ErrorDeNegocio.de(400, "DESP_ERROR_MOTIVO_INVALIDO", "El motivo no existe en el catálogo"));
        // NO_INTENTADO solo llega desde el cierre de jornada; los demás deben poder elegirse en campo.
        if (!noIntentado && (!fila[0] || !fila[2])) throw ErrorDeNegocio.de(400, "DESP_ERROR_MOTIVO_INVALIDO", "El motivo no se puede registrar en campo");
        return fila[1];
    }

    /** Hitos del ciclo vigente: lo anterior a la última asignación pertenece a salidas previas. */
    private Hitos hitos(UUID id) {
        return sql.sql("""
                WITH ciclo AS (SELECT max(registrado_en) AS desde FROM historial_estados_despacho
                    WHERE despacho_id = :id AND tipo_operacion = 'TRANSICION' AND estado_nuevo = 'ASIGNADO')
                SELECT c.desde,
                    max(h.registrado_en) FILTER (WHERE h.estado_nuevo = 'EN_CAMINO') AS en_camino,
                    max(h.registrado_en) FILTER (WHERE h.estado_nuevo = 'ENTREGADO') AS entregado,
                    max(h.registrado_en) FILTER (WHERE h.estado_nuevo = 'FALLIDO') AS fallido
                FROM ciclo c LEFT JOIN historial_estados_despacho h ON h.despacho_id = :id
                    AND h.tipo_operacion = 'TRANSICION' AND h.registrado_en >= c.desde
                GROUP BY c.desde
                """).param("id", id).query((r, i) -> new Hitos(instante(r.getObject(1, OffsetDateTime.class)),
                        instante(r.getObject(2, OffsetDateTime.class)), instante(r.getObject(3, OffsetDateTime.class)),
                        instante(r.getObject(4, OffsetDateTime.class)))).single();
    }
    private static Instant instante(OffsetDateTime valor) { return valor == null ? null : valor.toInstant(); }

    private void eventoVentas(Despacho despacho, EstadoDespacho anterior, SolicitudTransicion s, Instant ahora) {
        Map<String, Object> datos = new LinkedHashMap<>();
        if ("ENTREGADO".equals(s.estadoDestino())) {
            datos.put("fechaEntrega", ahora);
            if (s.recibidoPor() != null) datos.put("recibidoPor", s.recibidoPor());
        }
        if ("FALLIDO".equals(s.estadoDestino())) { datos.put("motivo", s.motivo()); datos.put("numeroIntento", despacho.getNumeroIntentos()); }
        UUID evento = UUID.randomUUID();
        String tipo = "DESPACHO_" + s.estadoDestino();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idEvento", evento.toString()); payload.put("tipo", tipo); payload.put("idPedido", despacho.getIdPedido());
        payload.put("idDespacho", despacho.getId().toString()); payload.put("estadoAnterior", anterior.name());
        payload.put("estadoNuevo", s.estadoDestino()); payload.put("ocurridoEn", ahora); payload.put("datos", datos);
        sql.sql("INSERT INTO eventos_salientes(id, despacho_id, destino, tipo_evento, payload) VALUES (:id, :despacho, 'VENTAS', :tipo, CAST(:payload AS jsonb))")
                .param("id", evento).param("despacho", despacho.getId()).param("tipo", tipo).param("payload", json.writeValueAsString(payload)).update();
    }

    private Map<String, Object> proyeccion(Despacho despacho, String motivo, Hitos hitos, Instant ahora) {
        Map<String, Object> proyeccion = new LinkedHashMap<>();
        proyeccion.put("idDespacho", despacho.getId().toString()); proyeccion.put("idPedido", despacho.getIdPedido());
        proyeccion.put("codigoRastreoInterno", despacho.getCodigoRastreoInterno()); proyeccion.put("estado", despacho.getEstado().name());
        proyeccion.put("idJornada", despacho.getIdJornada()); proyeccion.put("idReserva", despacho.getIdReserva());
        proyeccion.put("secuenciaRuta", despacho.getSecuenciaRuta());
        proyeccion.put("destinatario", new Destinatario(despacho.getDestinatarioNombre(), despacho.getDestinatarioTelefono()));
        proyeccion.put("destino", new Destino(despacho.getDireccion(), despacho.getReferencia(), despacho.getDepartamento(),
                despacho.getProvincia(), despacho.getDistrito(), despacho.getCodigoPostal(), null));
        proyeccion.put("fechaProgramada", despacho.getFechaProgramada()); proyeccion.put("numeroIntentos", despacho.getNumeroIntentos());
        proyeccion.put("maximoIntentos", sql.sql("SELECT CAST(valor AS integer) FROM parametros_configuracion WHERE clave='MAXIMO_INTENTOS'")
                .query(Integer.class).single());
        proyeccion.put("motivoFallo", motivo); proyeccion.put("hitos", hitos);
        proyeccion.put("version", despacho.getVersion()); proyeccion.put("actualizadoEn", ahora);
        return proyeccion;
    }

    private void guardarEvento(UUID despacho, String destino, String tipo, Object payload) {
        sql.sql("INSERT INTO eventos_salientes(id, despacho_id, destino, tipo_evento, payload) VALUES (:id, :despacho, :destino, :tipo, CAST(:payload AS jsonb))")
                .param("id", UUID.randomUUID()).param("despacho", despacho).param("destino", destino).param("tipo", tipo)
                .param("payload", json.writeValueAsString(payload)).update();
    }
    private String huella(UUID id, SolicitudTransicion s) {
        try {
            byte[] contenido = json.writeValueAsString(Map.of("idDespacho", id.toString(), "solicitud", s)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private void bloquear(String clave) { sql.sql("SELECT pg_advisory_xact_lock(hashtextextended(:clave, 0))").param("clave", clave).query((fila, indice) -> true).single(); }
    private static ErrorDeNegocio conflicto(String codigo, String mensaje) { return ErrorDeNegocio.de(409, codigo, mensaje); }
}
