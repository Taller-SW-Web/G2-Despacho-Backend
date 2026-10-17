package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.DetalleIncidencia;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LectorDetalleIncidencia;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.AccionesIncidencia;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosIncidenciaInconsistentesException;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Jornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Repartidor;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.DetalleIncidencia.*;

@Repository
@Profile("!integracion-local")
public class DetalleIncidenciaJdbc implements LectorDetalleIncidencia {
    private final JdbcClient sql;
    private final JsonMapper json;

    public DetalleIncidenciaJdbc(JdbcClient sql, JsonMapper json) { this.sql = sql; this.json = json; }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DetalleIncidencia consultar(String idDespacho) {
        var base = sql.sql("""
                SELECT d.id, d.codigo_rastreo_interno, d.estado, d.version, d.actualizado_en,
                  d.numero_intentos, d.pedido_anulado_en, d.ultima_incidencia_id,
                  i.id AS id_incidencia, i.registrado_en, i.comentario, i.consume_intento,
                  i.repartidor_id_externo, i.asignacion_diaria_id_externa, i.evidencia_id_externa,
                  i.recibido_en_centro_en, i.recibido_por, i.sello_intacto, i.observacion_recepcion,
                  m.codigo AS motivo_codigo, m.etiqueta AS motivo_etiqueta,
                  l.id AS id_liberacion, l.estado AS estado_liberacion, l.requiere_revision,
                  l.actualizado_en AS liberacion_actualizada_en, l.confirmada_en AS liberacion_confirmada_en,
                  EXISTS(SELECT 1 FROM intentos_entrega previa WHERE previa.despacho_id = d.id
                    AND previa.resultado = 'FALLIDO') AS tiene_incidencias,
                  (SELECT valor FROM parametros_configuracion WHERE clave = 'MAXIMO_INTENTOS') AS maximo_intentos,
                  (SELECT max(h.registrado_en) FROM historial_estados_despacho h
                    WHERE h.despacho_id = d.id AND h.tipo_operacion = 'TRANSICION') AS ultimo_cambio_estado_en
                FROM despachos d
                LEFT JOIN intentos_entrega i ON i.id = d.ultima_incidencia_id AND i.despacho_id = d.id AND i.resultado = 'FALLIDO'
                LEFT JOIN motivos_fallo m ON m.codigo = i.motivo_codigo
                LEFT JOIN liberaciones_capacidad l ON l.incidencia_id = i.id AND l.despacho_id = d.id
                WHERE d.id::text = :id
                """).param("id", idDespacho).query((fila, indice) -> leerBase(fila)).optional()
                .orElseThrow(() -> ErrorDeNegocio.de(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe"));
        var historial = sql.sql("""
                SELECT h.*, m.etiqueta AS motivo_etiqueta FROM historial_estados_despacho h
                LEFT JOIN motivos_fallo m ON m.codigo = h.motivo_codigo
                WHERE h.despacho_id::text = :id ORDER BY h.registrado_en, h.id
                """).param("id", idDespacho).query((fila, indice) -> new EntradaHistorial(fila.getString("id"),
                        instante(fila, "registrado_en"), fila.getString("tipo_operacion"), fila.getString("estado_anterior"),
                        fila.getString("estado_nuevo"), fila.getString("funcionalidad_origen"), fila.getString("ejecutor_tipo"),
                        fila.getString("ejecutor_id"), fila.getString("origen"), motivo(fila), fila.getString("observaciones"),
                        datosPresentacion(fila.getString("datos"), fila.getString("tipo_operacion"), fila.getString("estado_nuevo")))).list();
        var comunicaciones = sql.sql("""
                SELECT id, tipo_evento, estado_envio, creado_en, enviado_en FROM eventos_salientes
                WHERE despacho_id::text = :id AND destino = 'VENTAS' ORDER BY creado_en, id
                """).param("id", idDespacho).query((fila, indice) -> new Comunicacion(fila.getString("id"),
                        fila.getString("tipo_evento"), fila.getString("estado_envio"), instante(fila, "creado_en"),
                        instante(fila, "enviado_en"))).list();
        return new DetalleIncidencia(base.idDespacho(), base.codigoRastreoInterno(), base.estado(), base.versionDespacho(),
                base.actualizadoEn(), base.ultimoCambioEstadoEn(), base.consultadoEn(), base.numeroIntentos(),
                base.maximoIntentos(), base.pedidoAnulado(), base.incidencia(), historial, comunicaciones, base.acciones());
    }

    private DetalleIncidencia leerBase(ResultSet fila) throws SQLException {
        if (fila.getString("id_incidencia") == null) {
            if (fila.getString("ultima_incidencia_id") != null || fila.getBoolean("tiene_incidencias")
                    || "FALLIDO".equals(fila.getString("estado"))) throw inconsistencia();
            throw ErrorDeNegocio.de(404, "DESP_ERROR_INCIDENCIA_NO_ENCONTRADA", "El despacho no tiene una incidencia registrada");
        }
        String politica = fila.getString("maximo_intentos");
        int maximo;
        try { maximo = Integer.parseInt(politica); } catch (RuntimeException error) { throw inconsistencia(); }
        if (maximo < 1 || fila.getString("codigo_rastreo_interno") == null || motivo(fila) == null || fila.getString("motivo_etiqueta") == null
                || fila.getString("repartidor_id_externo") == null || fila.getString("asignacion_diaria_id_externa") == null
                || instante(fila, "ultimo_cambio_estado_en") == null) throw inconsistencia();
        boolean recibido = instante(fila, "recibido_en_centro_en") != null;
        LiberacionCapacidad liberacion = null;
        if (recibido) {
            if (fila.getString("recibido_por") == null || fila.getObject("sello_intacto") == null || fila.getString("id_liberacion") == null) throw inconsistencia();
            String estado = fila.getString("estado_liberacion");
            boolean confirmada = "CONFIRMADA".equals(estado);
            if (!(confirmada || "PENDIENTE".equals(estado)) || confirmada != (instante(fila, "liberacion_confirmada_en") != null)
                    || confirmada && fila.getBoolean("requiere_revision")) throw inconsistencia();
            liberacion = new LiberacionCapacidad(fila.getString("id_liberacion"), estado, fila.getBoolean("requiere_revision"),
                    instante(fila, "liberacion_actualizada_en"), instante(fila, "liberacion_confirmada_en"));
        } else if (fila.getString("recibido_por") != null || fila.getObject("sello_intacto") != null
                || fila.getString("observacion_recepcion") != null || fila.getString("id_liberacion") != null) throw inconsistencia();
        var recepcion = new Recepcion(recibido ? "RECIBIDO_EN_CENTRO" : "PENDIENTE_RETORNO", instante(fila, "recibido_en_centro_en"),
                fila.getString("recibido_por"), (Boolean) fila.getObject("sello_intacto"), fila.getString("observacion_recepcion"), liberacion);
        String idEvidencia = fila.getString("evidencia_id_externa");
        var evidencia = new Evidencia(idEvidencia, idEvidencia != null ? "REFERENCIADA"
                : "NO_INTENTADO".equals(fila.getString("motivo_codigo")) ? "SIN_EVIDENCIA" : "REFERENCIA_AUSENTE");
        var incidencia = new Incidencia(fila.getString("id_incidencia"), instante(fila, "registrado_en"), motivo(fila),
                fila.getString("comentario"), fila.getBoolean("consume_intento"), new Repartidor(fila.getString("repartidor_id_externo"), null),
                Jornada.desconocida(fila.getString("asignacion_diaria_id_externa"), null), null, recepcion, evidencia);
        return new DetalleIncidencia(fila.getString("id"), fila.getString("codigo_rastreo_interno"), fila.getString("estado"),
                fila.getInt("version"), instante(fila, "actualizado_en"), instante(fila, "ultimo_cambio_estado_en"), null,
                fila.getInt("numero_intentos"), maximo, fila.getObject("pedido_anulado_en") != null, incidencia, List.of(), List.of(),
                AccionesIncidencia.calcular(fila.getString("estado"), recibido, fila.getObject("pedido_anulado_en") != null,
                        fila.getInt("numero_intentos"), maximo));
    }

    /** Solo campos contractuales, con tipos comprobados; jamás payloads internos o credenciales. */
    Map<String, Object> datosPresentacion(String contenido, String operacion, String estadoNuevo) {
        tools.jackson.databind.JsonNode origen;
        try { origen = json.readTree(contenido); } catch (RuntimeException error) { throw inconsistencia(); }
        if (origen == null || !origen.isObject()) throw inconsistencia();
        List<String> permitidos = switch (operacion) {
            case "RECEPCION_EN_CENTRO" -> List.of("idIncidencia", "idReserva", "selloIntacto", "observacionRecepcion");
            case "REASIGNACION" -> List.of("idRepartidor", "idJornada", "idNuevoRepartidor", "idNuevaJornada");
            case "REORDENAMIENTO" -> List.of("despachos");
            case "TRANSICION" -> switch (estadoNuevo) {
                case "FALLIDO" -> List.of("idIncidencia", "idRepartidor", "idJornada", "idReserva", "consumeIntento", "numeroIntentos");
                case "PENDIENTE_ASIGNACION" -> List.of("idIncidencia", "nuevaFechaProgramada");
                case "DEVUELTO_A_ORIGEN" -> List.of("idIncidencia", "motivoCierre");
                case "ASIGNADO" -> List.of("idRepartidor", "idJornada");
                default -> List.of();
            };
            default -> List.of();
        };
        Map<String, Object> resultado = new LinkedHashMap<>();
        for (String campo : permitidos) {
            var valor = origen.get(campo);
            if (valor == null) continue;
            if ("despachos".equals(campo)) {
                if (!valor.isArray()) throw inconsistencia();
                var posiciones = new java.util.ArrayList<Map<String, Object>>();
                for (var posicion : valor) {
                    if (!posicion.path("idDespacho").isString() || !posicion.path("posicion").isIntegralNumber()
                            || posicion.path("posicion").asInt() < 1) throw inconsistencia();
                    posiciones.add(Map.of("idDespacho", posicion.path("idDespacho").asString(), "posicion", posicion.path("posicion").asInt()));
                }
                resultado.put(campo, posiciones);
            } else if (List.of("consumeIntento", "selloIntacto").contains(campo)) {
                if (!valor.isBoolean()) throw inconsistencia();
                resultado.put(campo, valor.asBoolean());
            } else if ("numeroIntentos".equals(campo)) {
                if (!valor.isIntegralNumber() || valor.asInt() < 0) throw inconsistencia();
                resultado.put(campo, valor.asInt());
            } else {
                if (!valor.isString() && !("observacionRecepcion".equals(campo) && valor.isNull())) throw inconsistencia();
                resultado.put(campo, valor.isNull() ? null : valor.asString());
            }
        }
        return resultado;
    }

    private static Motivo motivo(ResultSet fila) throws SQLException {
        String codigo = fila.getString("motivo_codigo");
        return codigo == null ? null : new Motivo(codigo, fila.getString("motivo_etiqueta"));
    }
    private static Instant instante(ResultSet fila, String campo) throws SQLException {
        var fecha = fila.getObject(campo, OffsetDateTime.class); return fecha == null ? null : fecha.toInstant();
    }
    private static DatosIncidenciaInconsistentesException inconsistencia() {
        return new DatosIncidenciaInconsistentesException("El detalle contiene referencias o datos canónicos inconsistentes");
    }
}
