package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.time.*;
import java.util.*;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.AlmacenProgramacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.Despacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.EstadoDespacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

@Repository
@Profile("!integracion-local")
public class ProgramacionPersistencia implements AlmacenProgramacion {
    private final JdbcClient sql;
    private final EntityManager entidades;
    private final JsonMapper json;
    private static final String REINTENTO = """
            EXISTS (SELECT 1 FROM historial_estados_despacho h WHERE h.despacho_id = d.id
              AND h.tipo_operacion = 'TRANSICION' AND h.estado_anterior = 'FALLIDO'
              AND h.estado_nuevo = 'PENDIENTE_ASIGNACION' AND h.funcionalidad_origen = 'F-04'
              AND jsonb_exists(h.datos, 'nuevaFechaProgramada'))
            """;
    public ProgramacionPersistencia(JdbcClient sql, EntityManager entidades, JsonMapper json) {
        this.sql = sql; this.entidades = entidades; this.json = json;
    }

    @Override
    @Transactional
    public Optional<ResultadoCreacion> recuperarCreacion(String clave, String huella, String pedido, String actor) {
        bloquear("recepcion-clave:" + actor + ":" + clave);
        var repetida = sql.sql("""
                SELECT huella_solicitud, codigo_respuesta, respuesta::text FROM claves_idempotencia
                WHERE actor_tipo = 'SERVICIO' AND actor_id = :actor AND operacion = 'CREAR_DESPACHO' AND clave = :clave
                """).param("actor", actor).param("clave", clave).query((fila, indice) -> {
                    if (!huella.equals(fila.getString(1))) throw conflicto("DESP_ERROR_OPERACION_REUTILIZADA", "La clave ya fue utilizada con otra solicitud");
                    return new ResultadoCreacion(fila.getInt(2), json.readValue(fila.getString(3), RespuestaCreacion.class));
                }).optional();
        if (repetida.isPresent()) return repetida;
        bloquear("recepcion-pedido:" + pedido);
        var ids = sql.sql("SELECT id FROM despachos WHERE pedido_id = :pedido").param("pedido", pedido).query(UUID.class).list();
        if (ids.isEmpty()) return Optional.empty();
        var existente = new ResultadoCreacion(200, respuesta(entidades.find(Despacho.class, ids.getFirst())));
        guardarClave(clave, huella, actor, existente);
        return Optional.of(existente);
    }

    @Override
    @Transactional
    public ResultadoCreacion crear(Despacho despacho, String clave, String huella, String actor, String observacion) {
        if (clave != null) {
            // La segunda comprobación está en la misma transacción que la inserción.
            var existente = recuperarCreacion(clave, huella, despacho.getIdPedido(), actor);
            if (existente.isPresent()) return existente.get();
        }
        entidades.persist(despacho);
        entidades.flush();
        historial(despacho, null, despacho.isEsSimulacion() ? "USUARIO" : "SERVICIO", actor,
                observacion, Map.of("esSimulacion", despacho.isEsSimulacion()), despacho.getCreadoEn());
        var resultado = new ResultadoCreacion(201, respuesta(despacho));
        if (clave != null) guardarClave(clave, huella, actor, resultado);
        return resultado;
    }

    private void guardarClave(String clave, String huella, String actor, ResultadoCreacion resultado) {
        sql.sql("""
                INSERT INTO claves_idempotencia(clave, actor_tipo, actor_id, operacion, huella_solicitud, estado, codigo_respuesta, respuesta)
                VALUES (:clave, 'SERVICIO', :actor, 'CREAR_DESPACHO', :huella, 'COMPLETADA', :codigo, CAST(:respuesta AS jsonb))
                """).param("clave", clave).param("actor", actor).param("huella", huella).param("codigo", resultado.codigoHttp())
                .param("respuesta", json.writeValueAsString(resultado.respuesta())).update();
    }

    @Override public long siguienteCodigo() { return sql.sql("SELECT nextval('secuencia_codigos_despacho')").query(Long.class).single(); }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PaginaPendientes consultar(FiltrosPendientes filtros, LocalDate hoy, Instant ahora) {
        Indicadores indicadores = sql.sql("""
                SELECT count(*) FILTER (WHERE fecha_programada <= :hoy) AS hoy,
                    count(*) FILTER (WHERE fecha_programada > :hoy) AS futuro,
                    count(*) FILTER (WHERE %s) AS reintentos FROM despachos d WHERE estado = 'PENDIENTE_ASIGNACION'
                """.formatted(REINTENTO)).param("hoy", hoy).query((fila, indice) -> new Indicadores(
                        fila.getLong("hoy"), fila.getLong("futuro"), fila.getLong("reintentos"))).single();
        String predicado = "d.estado = 'PENDIENTE_ASIGNACION'";
        Map<String, Object> parametros = new HashMap<>();
        if (filtros.idZona() != null) { predicado += " AND d.zona_id::text = :zona"; parametros.put("zona", filtros.idZona()); }
        if (filtros.fechaDesde() != null) { predicado += " AND d.fecha_programada >= :desde"; parametros.put("desde", filtros.fechaDesde()); }
        if (filtros.fechaHasta() != null) { predicado += " AND d.fecha_programada <= :hasta"; parametros.put("hasta", filtros.fechaHasta()); }
        if ("REINTENTOS".equals(filtros.tipoIntento())) predicado += " AND (" + REINTENTO + ")";
        if ("PRIMER_INTENTO".equals(filtros.tipoIntento())) predicado += " AND NOT (" + REINTENTO + ")";
        long total = sql.sql("SELECT count(*) FROM despachos d WHERE " + predicado).params(parametros).query(Long.class).single();
        parametros.put("limite", filtros.tamanoPagina()); parametros.put("salto", (long) (filtros.pagina() - 1) * filtros.tamanoPagina());
        var filas = sql.sql("SELECT d.*, z.nombre AS nombre_zona, (" + REINTENTO + ") AS reintento FROM despachos d LEFT JOIN zonas z ON z.id = d.zona_id WHERE "
                + predicado + " ORDER BY d.fecha_programada ASC, d.creado_en ASC, d.id ASC LIMIT :limite OFFSET :salto")
                .params(parametros).query((fila, indice) -> {
                    LocalDate fecha = fila.getObject("fecha_programada", LocalDate.class);
                    OffsetDateTime entrada = fila.getObject("en_cola_desde", OffsetDateTime.class);
                    if (fecha == null || entrada == null || fila.getBigDecimal("peso_kg") == null || fila.getBigDecimal("volumen_m3") == null)
                        throw ErrorDeNegocio.de(500, "DESP_ERROR_DATOS_INCONSISTENTES", "El despacho no tiene datos operativos completos");
                    return new Pendiente(fila.getString("id"), fila.getString("codigo_operativo"), fila.getString("codigo_rastreo_interno"),
                            fila.getString("pedido_id"), fila.getString("zona_id"), fila.getString("nombre_zona"), fila.getString("direccion"),
                            fila.getBigDecimal("peso_kg"), fila.getBigDecimal("volumen_m3"), fila.getInt("cantidad_paquetes"), fecha,
                            fila.getInt("numero_intentos"), Math.max(0, Duration.between(entrada.toInstant(), ahora).getSeconds()),
                            fila.getBoolean("es_simulado"), fila.getBoolean("reintento"), !fecha.isAfter(hoy), fecha, fila.getInt("version"));
                }).list();
        return new PaginaPendientes(filas, filtros.pagina(), filtros.tamanoPagina(), total,
                (total + filtros.tamanoPagina() - 1) / filtros.tamanoPagina(), indicadores, hoy, ahora);
    }

    @Override
    @Transactional
    public IntencionAsignacion prepararAsignacion(UUID id, SolicitudAsignacion solicitud, String actor, LocalDate hoy) {
        bloquear("asignacion:" + id);
        GuardiaCancelacion.exigirLibre(sql, id);
        Despacho despacho = buscar(id);
        var anteriores = sql.sql("""
                SELECT a.* FROM asignaciones_despacho a WHERE a.despacho_id = :id
                AND a.estado NOT IN ('RECHAZADA', 'COMPENSADA') ORDER BY a.creado_en DESC, a.id DESC
                """).param("id", id).query(this::intencion).list();
        for (var anterior : anteriores) {
            boolean mismaVersion = anterior.versionOrigen() == despacho.getVersion();
            boolean resultadoVigente = despacho.getEstado() == EstadoDespacho.ASIGNADO && anterior.versionOrigen() + 1 == despacho.getVersion();
            if (!mismaVersion && !resultadoVigente) continue;
            if (!Objects.equals(anterior.ejecutor(), actor) || !Objects.equals(anterior.idRepartidor(), solicitud.idRepartidor())
                    || !Objects.equals(anterior.idJornada(), solicitud.idJornada()) || !Objects.equals(anterior.observacion(), solicitud.observacion()))
                throw conflicto("DESP_ERROR_OPERACION_REUTILIZADA", "Existe otra asignación para esta versión del despacho").conEstadoActual(despacho.getEstado().name());
            return anterior;
        }
        if (despacho.getEstado() != EstadoDespacho.PENDIENTE_ASIGNACION)
            throw conflicto("DESP_ERROR_ESTADO_INCOMPATIBLE", "El despacho ya fue procesado").conEstadoActual(despacho.getEstado().name());
        if (despacho.getFechaProgramada() == null) throw ErrorDeNegocio.de(500, "DESP_ERROR_DATOS_INCONSISTENTES", "El despacho no tiene fecha programada");
        if (despacho.getFechaProgramada().isAfter(hoy)) throw conflicto("DESP_ERROR_FECHA_PROGRAMADA_FUTURA", "Despacho programado para fecha futura");
        if (despacho.getPesoKg() == null || despacho.getVolumenM3() == null || despacho.getPesoKg().signum() <= 0
                || despacho.getVolumenM3().signum() <= 0 || despacho.getCantidadPaquetes() <= 0)
            throw ErrorDeNegocio.de(500, "DESP_ERROR_DATOS_INCONSISTENTES", "El despacho no tiene carga válida");
        UUID operacion = UUID.randomUUID();
        sql.sql("""
                INSERT INTO asignaciones_despacho(id, despacho_id, version_origen, ejecutor_id, id_repartidor, id_jornada,
                    observacion, peso_requerido, volumen_requerido, paquetes_requeridos, estado)
                VALUES (:operacion, :id, :version, :actor, :repartidor, :jornada, :observacion, :peso, :volumen, :paquetes, 'PENDIENTE')
                """).param("operacion", operacion).param("id", id).param("version", despacho.getVersion()).param("actor", actor)
                .param("repartidor", solicitud.idRepartidor()).param("jornada", solicitud.idJornada()).param("observacion", solicitud.observacion())
                .param("peso", despacho.getPesoKg()).param("volumen", despacho.getVolumenM3()).param("paquetes", despacho.getCantidadPaquetes()).update();
        return new IntencionAsignacion(operacion, id, despacho.getVersion(), actor, solicitud.idRepartidor(), solicitud.idJornada(),
                solicitud.observacion(), "PENDIENTE", null, null, new Carga(despacho.getPesoKg(), despacho.getVolumenM3(), despacho.getCantidadPaquetes()));
    }

    @Override
    @Transactional
    public boolean reclamar(UUID id) {
        return sql.sql("""
                UPDATE asignaciones_despacho SET procesando_hasta = now() + interval '60 seconds'
                WHERE id = :id AND estado IN ('PENDIENTE', 'COMPENSACION_PENDIENTE')
                AND (procesando_hasta IS NULL OR procesando_hasta < now())
                """).param("id", id).update() == 1;
    }

    @Override
    @Transactional
    public RespuestaAsignacion confirmarAsignacion(IntencionAsignacion intencion, ReservaConfirmada reserva, Instant ahora) {
        bloquear("asignacion:" + intencion.idDespacho());
        String estadoIntencion = sql.sql("SELECT estado FROM asignaciones_despacho WHERE id = :id FOR UPDATE")
                .param("id", intencion.id()).query(String.class).single();
        var repetida = resultadoAsignacion(intencion.id());
        if (repetida.isPresent()) return repetida.get();
        if (!"PENDIENTE".equals(estadoIntencion)) throw conflicto("DESP_ERROR_ASIGNACION_COMPENSADA", "La asignación está en compensación o ya fue rechazada");
        Despacho despacho = buscar(intencion.idDespacho());
        if (despacho.getVersion() != intencion.versionOrigen() || despacho.getEstado() != EstadoDespacho.PENDIENTE_ASIGNACION)
            throw conflicto("DESP_ERROR_DESPACHO_DESACTUALIZADO", "El despacho cambió mientras se reservaba capacidad").conEstadoActual(despacho.getEstado().name());
        bloquear("secuencia-jornada:" + intencion.idJornada());
        int posicion = sql.sql("SELECT coalesce(max(secuencia_ruta), 0) + 1 FROM despachos WHERE asignacion_diaria_id_externa = :jornada")
                .param("jornada", intencion.idJornada()).query(Integer.class).single();
        despacho.setEstado(EstadoDespacho.ASIGNADO); despacho.setIdRepartidor(intencion.idRepartidor());
        despacho.setIdJornada(intencion.idJornada()); despacho.setIdReserva(reserva.idReserva());
        despacho.setSecuenciaRuta(posicion); despacho.setObservacionAsignacion(intencion.observacion()); despacho.setActualizadoEn(ahora);
        entidades.flush(); // @Version agrega la comparación de versión a la escritura.
        historial(despacho, "PENDIENTE_ASIGNACION", "USUARIO", intencion.ejecutor(), intencion.observacion(),
                Map.of("idRepartidor", intencion.idRepartidor(), "idJornada", intencion.idJornada(), "idReserva", reserva.idReserva(),
                        "capacidadRemanente", reserva.capacidadRemanente(), "cargaReservada", intencion.carga(), "secuenciaRuta", posicion), ahora);
        var respuesta = new RespuestaAsignacion(despacho.getId().toString(), "ASIGNADO", despacho.getVersion(),
                intencion.idRepartidor(), intencion.idJornada(), reserva.idReserva(), posicion, intencion.observacion(), ahora);
        sql.sql("UPDATE asignaciones_despacho SET estado = 'CONFIRMADA', id_reserva = :reserva, respuesta = CAST(:respuesta AS jsonb), procesando_hasta = NULL WHERE id = :id")
                .param("id", intencion.id()).param("reserva", reserva.idReserva()).param("respuesta", json.writeValueAsString(respuesta)).update();
        if (!despacho.isEsSimulacion()) {
            UUID evento = UUID.randomUUID();
            guardarEvento(evento, despacho.getId(), "VENTAS", "DESPACHO_ASIGNADO", Map.of("idEvento", evento.toString(), "tipo", "DESPACHO_ASIGNADO",
                    "idPedido", despacho.getIdPedido(), "idDespacho", despacho.getId().toString(), "estadoAnterior", "PENDIENTE_ASIGNACION",
                    "estadoNuevo", "ASIGNADO", "ocurridoEn", ahora, "datos", Map.of("fechaProgramada", despacho.getFechaProgramada())));
        }
        publicarProyeccion(despacho, ahora);
        return respuesta;
    }

    /** Proyección del estado canónico dentro de la transacción de asignación, orden, reasignación o cancelación. */
    public void publicarProyeccion(Despacho despacho, Instant ahora) {
        Map<String, Object> proyeccion = new LinkedHashMap<>();
        proyeccion.put("idDespacho", despacho.getId().toString()); proyeccion.put("idPedido", despacho.getIdPedido());
        proyeccion.put("codigoRastreoInterno", despacho.getCodigoRastreoInterno()); proyeccion.put("estado", despacho.getEstado().name());
        proyeccion.put("idJornada", despacho.getIdJornada()); proyeccion.put("idReserva", despacho.getIdReserva());
        proyeccion.put("secuenciaRuta", despacho.getSecuenciaRuta()); proyeccion.put("destinatario", new Destinatario(despacho.getDestinatarioNombre(), despacho.getDestinatarioTelefono()));
        proyeccion.put("destino", new Destino(despacho.getDireccion(), despacho.getReferencia(), despacho.getDepartamento(), despacho.getProvincia(), despacho.getDistrito(), despacho.getCodigoPostal(), null));
        proyeccion.put("fechaProgramada", despacho.getFechaProgramada()); proyeccion.put("numeroIntentos", despacho.getNumeroIntentos());
        proyeccion.put("maximoIntentos", sql.sql("SELECT CAST(valor AS integer) FROM parametros_configuracion WHERE clave='MAXIMO_INTENTOS'")
                .query(Integer.class).single());
        var asignadoEn = sql.sql("SELECT max(registrado_en) FROM historial_estados_despacho WHERE despacho_id=:id AND tipo_operacion='TRANSICION' AND estado_nuevo='ASIGNADO'")
                .param("id", despacho.getId()).query((fila, indice) -> fila.getObject(1, OffsetDateTime.class)).list().getFirst();
        if (asignadoEn != null) proyeccion.put("hitos", Map.of("asignadoEn", asignadoEn.toInstant()));
        proyeccion.put("version", despacho.getVersion()); proyeccion.put("actualizadoEn", ahora);
        guardarEvento(UUID.randomUUID(), despacho.getId(), "OPERACION", "PROYECCION_DESPACHO", proyeccion);
    }

    private void guardarEvento(UUID id, UUID despacho, String destino, String tipo, Object payload) {
        sql.sql("INSERT INTO eventos_salientes(id, despacho_id, destino, tipo_evento, payload) VALUES (:id, :despacho, :destino, :tipo, CAST(:payload AS jsonb))")
                .param("id", id).param("despacho", despacho).param("destino", destino).param("tipo", tipo).param("payload", json.writeValueAsString(payload)).update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RespuestaAsignacion> resultadoAsignacion(UUID id) {
        return sql.sql("SELECT respuesta::text FROM asignaciones_despacho WHERE id = :id AND estado = 'CONFIRMADA'")
                .param("id", id).query((fila, indice) -> json.readValue(fila.getString(1), RespuestaAsignacion.class)).optional();
    }
    @Override @Transactional public boolean prepararCompensacion(UUID id, String reserva) {
        return sql.sql("UPDATE asignaciones_despacho SET estado = 'COMPENSACION_PENDIENTE', id_reserva = :reserva WHERE id = :id AND estado IN ('PENDIENTE', 'COMPENSACION_PENDIENTE')")
                .param("id", id).param("reserva", reserva).update() == 1;
    }
    @Override @Transactional public void finalizarCompensacion(UUID id) {
        sql.sql("UPDATE asignaciones_despacho SET estado = 'COMPENSADA', procesando_hasta = NULL WHERE id = :id AND estado = 'COMPENSACION_PENDIENTE'").param("id", id).update();
    }
    @Override @Transactional public void aplazar(UUID id) {
        sql.sql("UPDATE asignaciones_despacho SET procesando_hasta = NULL, proximo_intento_en = now() + interval '30 seconds' WHERE id = :id").param("id", id).update();
    }
    @Override @Transactional public void rechazar(UUID id) {
        sql.sql("UPDATE asignaciones_despacho SET estado = 'RECHAZADA', procesando_hasta = NULL WHERE id = :id AND estado = 'PENDIENTE'").param("id", id).update();
    }
    @Override @Transactional(readOnly = true) public List<IntencionAsignacion> recuperables() {
        return sql.sql("""
                SELECT a.* FROM asignaciones_despacho a WHERE a.estado IN ('PENDIENTE', 'COMPENSACION_PENDIENTE')
                AND a.proximo_intento_en <= now() AND (a.procesando_hasta IS NULL OR a.procesando_hasta < now())
                ORDER BY a.creado_en, a.id LIMIT 20
                """).query(this::intencion).list();
    }
    private IntencionAsignacion intencion(java.sql.ResultSet fila, int indice) throws java.sql.SQLException {
        String respuesta = fila.getString("respuesta");
        return new IntencionAsignacion(fila.getObject("id", UUID.class), fila.getObject("despacho_id", UUID.class), fila.getInt("version_origen"),
                fila.getString("ejecutor_id"), fila.getString("id_repartidor"), fila.getString("id_jornada"), fila.getString("observacion"),
                fila.getString("estado"), fila.getString("id_reserva"), respuesta == null ? null : json.readValue(respuesta, RespuestaAsignacion.class),
                new Carga(fila.getBigDecimal("peso_requerido"), fila.getBigDecimal("volumen_requerido"), fila.getInt("paquetes_requeridos")));
    }
    private Despacho buscar(UUID id) {
        Despacho despacho = entidades.find(Despacho.class, id);
        if (despacho == null) throw ErrorDeNegocio.de(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe");
        return despacho;
    }
    private void bloquear(String clave) { sql.sql("SELECT pg_advisory_xact_lock(hashtextextended(:clave, 0))").param("clave", clave).query((fila, indice) -> true).single(); }
    private RespuestaCreacion respuesta(Despacho despacho) {
        return new RespuestaCreacion(despacho.getId().toString(), despacho.getIdPedido(), despacho.getCodigoRastreoInterno(), despacho.getEstado().name(),
                despacho.getIdZona().toString(), despacho.getFechaProgramada(), despacho.getCreadoEn(), despacho.getCodigoOperativo(),
                despacho.isEsSimulacion(), despacho.getPesoKg(), despacho.getVolumenM3(), despacho.getCantidadPaquetes());
    }
    private void historial(Despacho despacho, String anterior, String tipoActor, String actor, String observacion, Object datos, Instant ahora) {
        sql.sql("""
                INSERT INTO historial_estados_despacho(despacho_id, tipo_operacion, estado_anterior, estado_nuevo, funcionalidad_origen,
                    ejecutor_tipo, ejecutor_id, origen, observaciones, datos, registrado_en)
                VALUES (:id, 'TRANSICION', :anterior, :nuevo, 'F-02', :tipo, :actor, :origen, :observacion, CAST(:datos AS jsonb), :ahora)
                """).param("id", despacho.getId()).param("anterior", anterior).param("nuevo", despacho.getEstado().name())
                .param("tipo", tipoActor).param("actor", actor).param("origen", "SERVICIO".equals(tipoActor) ? "AUTOMATICO" : "MANUAL")
                .param("observacion", observacion).param("datos", json.writeValueAsString(datos)).param("ahora", ahora.atOffset(ZoneOffset.UTC)).update();
    }
    private static ErrorDeNegocio conflicto(String codigo, String mensaje) { return ErrorDeNegocio.de(409, codigo, mensaje); }
}
