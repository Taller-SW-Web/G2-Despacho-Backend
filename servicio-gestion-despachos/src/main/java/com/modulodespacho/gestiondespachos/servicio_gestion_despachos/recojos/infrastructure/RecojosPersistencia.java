package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.Destinatario;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.Destino;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.application.ModelosRecojo.*;

/** Estado canónico del recojo: cada cambio guarda estado, historial y eventos en una sola transacción. */
@Repository
@Profile("!integracion-local")
public class RecojosPersistencia {
    private static final TypeReference<List<Linea>> LINEAS = new TypeReference<>() {};
    private static final String VISTA = """
            SELECT r.*, m.etiqueta AS etiqueta_motivo FROM recojos r LEFT JOIN motivos_fallo m ON m.codigo = r.motivo_codigo
            """;
    private final JdbcClient sql;
    private final JsonMapper json;
    public RecojosPersistencia(JdbcClient sql, JsonMapper json) { this.sql = sql; this.json = json; }

    @Transactional
    public Recojo crearSimulado(UUID idDespacho, List<Linea> lineas, String actor, LocalDate hoy, Instant ahora) {
        bloquear("recojo-despacho:" + idDespacho);
        var despacho = sql.sql("SELECT estado, es_simulado, codigo_rastreo_interno FROM despachos WHERE id = :id").param("id", idDespacho)
                .query((f, n) -> new Object[] {f.getString(1), f.getBoolean(2), f.getString(3)}).optional()
                .orElseThrow(() -> ErrorDeNegocio.de(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe"));
        if (!"ENTREGADO".equals(despacho[0])) throw conflicto("DESP_ERROR_ESTADO_INCOMPATIBLE", "Solo se recoge un producto de un despacho entregado");
        if (!(boolean) despacho[1]) throw conflicto("DESP_ERROR_RECOJO_SOLO_SIMULADO", "Un recojo de prueba solo se genera sobre un despacho simulado");
        UUID id = UUID.randomUUID();
        // Un despacho simulado no tiene productos; su carga completa representa lo que se devuelve.
        List<Linea> contenido = lineas == null || lineas.isEmpty() ? List.of(new Linea("SIM-" + despacho[2], 1)) : lineas;
        try {
            sql.sql("""
                    INSERT INTO recojos(id, despacho_id, codigo_rastreo_interno, estado, es_simulado, contacto_nombre, contacto_telefono,
                        direccion, referencia, departamento, provincia, distrito, codigo_postal, zona_id, lineas, peso_kg, volumen_m3,
                        cantidad_paquetes, fecha_programada, creado_en, actualizado_en)
                    SELECT :id, d.id, d.codigo_rastreo_interno, 'PENDIENTE_ASIGNACION', true, d.destinatario_nombre, d.destinatario_telefono,
                        d.direccion, d.referencia, d.departamento, d.provincia, d.distrito, d.codigo_postal, d.zona_id, CAST(:lineas AS jsonb),
                        d.peso_kg, d.volumen_m3, 1, :hoy, :ahora, :ahora FROM despachos d WHERE d.id = :despacho
                    """).param("id", id).param("despacho", idDespacho).param("lineas", json.writeValueAsString(contenido))
                    .param("hoy", hoy).param("ahora", fecha(ahora)).update();
        } catch (DuplicateKeyException error) {
            throw conflicto("DESP_ERROR_RECOJO_ACTIVO", "Ya existe un recojo activo para ese despacho");
        }
        historial(id, null, "PENDIENTE_ASIGNACION", "F-02", "USUARIO", actor, "MANUAL", null, null, Map.of("esSimulado", true), ahora);
        return buscar(id);
    }

    @Transactional(readOnly = true)
    public Pagina listar(List<String> estados, int pagina, int tamano) {
        long total = sql.sql("SELECT count(*) FROM recojos WHERE estado IN (:estados)").param("estados", estados).query(Long.class).single();
        var filas = sql.sql(VISTA + " WHERE r.estado IN (:estados) ORDER BY r.fecha_programada, r.creado_en, r.id LIMIT :limite OFFSET :salto")
                .param("estados", estados).param("limite", tamano).param("salto", (long) (pagina - 1) * tamano).query(this::recojo).list();
        return new Pagina(filas, pagina, tamano, total, (total + tamano - 1) / tamano);
    }

    /** Despachos simulados entregados y sin recojo activo: son los únicos que admiten un recojo de prueba. */
    @Transactional(readOnly = true)
    public List<DespachoElegible> elegibles() {
        return sql.sql("""
                SELECT d.id, d.codigo_rastreo_interno, d.destinatario_nombre, d.direccion, d.distrito FROM despachos d
                WHERE d.es_simulado AND d.estado = 'ENTREGADO' AND NOT EXISTS (SELECT 1 FROM recojos r WHERE r.despacho_id = d.id
                    AND r.estado NOT IN ('RECIBIDO_EN_CENTRO', 'CERRADO_SIN_RECOJO', 'CANCELADO'))
                ORDER BY d.actualizado_en DESC, d.id LIMIT 50
                """).query((f, n) -> new DespachoElegible(f.getString(1), f.getString(2), f.getString(3), f.getString(4), f.getString(5))).list();
    }

    @Transactional(readOnly = true)
    public DetalleRecojo detalle(UUID id) {
        Recojo recojo = buscar(id);
        var cambios = sql.sql("""
                SELECT estado_anterior, estado_nuevo, ejecutor_id, motivo_codigo, observaciones, registrado_en
                FROM historial_estados_recojo WHERE recojo_id = :id ORDER BY registrado_en, id
                """).param("id", id).query((f, n) -> new CambioHistorial(f.getString(1), f.getString(2), f.getString(3), f.getString(4), f.getString(5),
                        f.getObject(6, OffsetDateTime.class).toInstant())).list();
        return new DetalleRecojo(recojo, cambios);
    }

    @Transactional
    public Intencion prepararAsignacion(UUID id, UUID clave, int version, SolicitudAsignacion solicitud, String actor, LocalDate hoy) {
        bloquear("recojo:" + id);
        Recojo recojo = buscar(id);
        var anterior = sql.sql("SELECT a.*, r.despacho_id, r.peso_kg, r.volumen_m3, r.cantidad_paquetes FROM asignaciones_recojo a JOIN recojos r ON r.id = a.recojo_id "
                + "WHERE a.recojo_id = :id AND a.clave = :clave AND a.estado <> 'RECHAZADA'").param("id", id).param("clave", clave).query(this::intencion).optional();
        if (anterior.isPresent()) {
            Intencion previa = anterior.get();
            if (!actor.equals(previa.ejecutor()) || !solicitud.idRepartidor().equals(previa.idRepartidor()) || !solicitud.idJornada().equals(previa.idJornada()))
                throw conflicto("DESP_ERROR_OPERACION_REUTILIZADA", "La clave ya fue utilizada con otra solicitud");
            if ("COMPENSADA".equals(previa.estado())) throw conflicto("DESP_ERROR_ASIGNACION_COMPENSADA", "La asignación anterior no fue confirmada; usa una clave nueva");
            return previa;
        }
        exigirVersion(recojo, version);
        exigirEstado(recojo, "PENDIENTE_ASIGNACION");
        if (recojo.fechaProgramada().isAfter(hoy)) throw conflicto("DESP_ERROR_FECHA_PROGRAMADA_FUTURA", "Recojo programado para una fecha futura");
        UUID operacion = UUID.randomUUID();
        sql.sql("""
                INSERT INTO asignaciones_recojo(id, recojo_id, clave, version_origen, ejecutor_id, id_repartidor, id_jornada, estado)
                VALUES (:operacion, :id, :clave, :version, :actor, :repartidor, :jornada, 'PENDIENTE')
                """).param("operacion", operacion).param("id", id).param("clave", clave).param("version", version).param("actor", actor)
                .param("repartidor", solicitud.idRepartidor()).param("jornada", solicitud.idJornada()).update();
        return new Intencion(operacion, id, UUID.fromString(recojo.idDespacho()), clave, version, actor, solicitud.idRepartidor(), solicitud.idJornada(),
                "PENDIENTE", null, null, recojo.pesoKg(), recojo.volumenM3(), recojo.cantidadPaquetes());
    }
    @Transactional
    public boolean reclamar(UUID id) {
        return sql.sql("""
                UPDATE asignaciones_recojo SET procesando_hasta = now() + interval '60 seconds'
                WHERE id = :id AND estado IN ('PENDIENTE', 'COMPENSACION_PENDIENTE') AND (procesando_hasta IS NULL OR procesando_hasta < now())
                """).param("id", id).update() == 1;
    }
    @Transactional
    public Recojo confirmarAsignacion(Intencion intencion, String reserva, Instant ahora) {
        bloquear("recojo:" + intencion.idRecojo());
        var repetida = resultadoAsignacion(intencion.id());
        if (repetida.isPresent()) return repetida.get();
        Recojo recojo = buscar(intencion.idRecojo());
        if (recojo.version() != intencion.versionOrigen() || !"PENDIENTE_ASIGNACION".equals(recojo.estado()))
            throw conflicto("DESP_ERROR_RECOJO_DESACTUALIZADO", "El recojo cambió mientras se reservaba capacidad").conEstadoActual(recojo.estado());
        bloquear("secuencia-jornada-recojos:" + intencion.idJornada());
        int posicion = sql.sql("SELECT coalesce(max(secuencia_ruta), 0) + 1 FROM recojos WHERE asignacion_diaria_id_externa = :jornada")
                .param("jornada", intencion.idJornada()).query(Integer.class).single();
        sql.sql("""
                UPDATE recojos SET estado = 'ASIGNADO', repartidor_id_externo = :repartidor, asignacion_diaria_id_externa = :jornada,
                    reserva_capacidad_id_externa = :reserva, secuencia_ruta = :posicion, motivo_codigo = NULL, evidencia_id_externa = NULL,
                    version = version + 1, actualizado_en = :ahora WHERE id = :id
                """).param("repartidor", intencion.idRepartidor()).param("jornada", intencion.idJornada()).param("reserva", reserva)
                .param("posicion", posicion).param("ahora", fecha(ahora)).param("id", intencion.idRecojo()).update();
        historial(intencion.idRecojo(), "PENDIENTE_ASIGNACION", "ASIGNADO", "F-02", "USUARIO", intencion.ejecutor(), "MANUAL", null, null,
                Map.of("idRepartidor", intencion.idRepartidor(), "idJornada", intencion.idJornada(), "idReserva", reserva, "secuenciaRuta", posicion), ahora);
        Recojo resultado = buscar(intencion.idRecojo());
        sql.sql("UPDATE asignaciones_recojo SET estado = 'CONFIRMADA', id_reserva = :reserva, respuesta = CAST(:respuesta AS jsonb), procesando_hasta = NULL WHERE id = :id")
                .param("reserva", reserva).param("respuesta", json.writeValueAsString(resultado)).param("id", intencion.id()).update();
        proyectar(intencion.idRecojo(), hitos(intencion.idRecojo()), ahora);
        return resultado;
    }
    @Transactional(readOnly = true)
    public Optional<Recojo> resultadoAsignacion(UUID id) {
        return sql.sql("SELECT respuesta::text FROM asignaciones_recojo WHERE id = :id AND estado = 'CONFIRMADA'").param("id", id)
                .query((f, n) -> json.readValue(f.getString(1), Recojo.class)).optional();
    }
    @Transactional public boolean prepararCompensacion(UUID id, String reserva) {
        return sql.sql("UPDATE asignaciones_recojo SET estado = 'COMPENSACION_PENDIENTE', id_reserva = :reserva WHERE id = :id AND estado IN ('PENDIENTE', 'COMPENSACION_PENDIENTE')")
                .param("id", id).param("reserva", reserva).update() == 1;
    }
    @Transactional public void finalizarCompensacion(UUID id) {
        sql.sql("UPDATE asignaciones_recojo SET estado = 'COMPENSADA', procesando_hasta = NULL WHERE id = :id AND estado = 'COMPENSACION_PENDIENTE'").param("id", id).update();
    }
    @Transactional public void aplazar(UUID id) {
        sql.sql("UPDATE asignaciones_recojo SET procesando_hasta = NULL, proximo_intento_en = now() + interval '30 seconds' WHERE id = :id").param("id", id).update();
    }
    @Transactional public void rechazar(UUID id) {
        sql.sql("UPDATE asignaciones_recojo SET estado = 'RECHAZADA', procesando_hasta = NULL WHERE id = :id AND estado = 'PENDIENTE'").param("id", id).update();
    }
    @Transactional(readOnly = true)
    public List<Intencion> recuperables() {
        return sql.sql("""
                SELECT a.*, r.despacho_id, r.peso_kg, r.volumen_m3, r.cantidad_paquetes FROM asignaciones_recojo a JOIN recojos r ON r.id = a.recojo_id
                WHERE a.estado IN ('PENDIENTE', 'COMPENSACION_PENDIENTE') AND a.proximo_intento_en <= now()
                  AND (a.procesando_hasta IS NULL OR a.procesando_hasta < now()) ORDER BY a.creado_en, a.id LIMIT 20
                """).query(this::intencion).list();
    }

    /** Gestión §6.3: única puerta de Operación hacia el estado del recojo; repetir la clave devuelve el resultado original. */
    @Transactional
    public RespuestaTransicion transicionar(UUID id, String clave, SolicitudTransicion s, String actor, Instant ahora) {
        String huella = huella(Map.of("idRecojo", id.toString(), "solicitud", s));
        bloquear("recojo-transicion-clave:" + actor + ":" + clave);
        var repetida = respuestaPrevia("SERVICIO", actor, "TRANSICION_RECOJO", clave, huella, RespuestaTransicion.class);
        if (repetida.isPresent()) return repetida.get();
        bloquear("recojo:" + id);
        Recojo recojo = sql.sql(VISTA + " WHERE r.id = :id").param("id", id).query(this::recojo).optional()
                .orElseThrow(() -> ErrorDeNegocio.de(404, "DESP_ERROR_RECOJO_NO_ENCONTRADO", "El recojo no existe"));
        if (!Objects.equals(recojo.idRepartidor(), s.idRepartidor()) || !Objects.equals(recojo.idJornada(), s.idJornada()))
            throw conflicto("DESP_ERROR_RECOJO_REASIGNADO", "El recojo ya no pertenece a esta ruta").conEstadoActual(recojo.estado());
        boolean noIntentado = "NO_INTENTADO".equals(s.motivo());
        boolean origenValido = switch (s.estadoDestino()) {
            case "EN_CAMINO" -> "ASIGNADO".equals(recojo.estado());
            case "RECOGIDO" -> "EN_CAMINO".equals(recojo.estado());
            default -> noIntentado ? Set.of("ASIGNADO", "EN_CAMINO").contains(recojo.estado()) : "EN_CAMINO".equals(recojo.estado());
        };
        if (!origenValido) throw conflicto("DESP_ERROR_ESTADO_INCOMPATIBLE", "El recojo ya no admite esta acción").conEstadoActual(recojo.estado());
        boolean fallido = "FALLIDO".equals(s.estadoDestino());
        boolean consume = fallido && validarMotivo(s.motivo(), noIntentado);
        sql.sql("""
                UPDATE recojos SET estado = :estado, numero_intentos = numero_intentos + :suma, motivo_codigo = :motivo,
                    evidencia_id_externa = CAST(:evidencia AS uuid), version = version + 1, actualizado_en = :ahora WHERE id = :id
                """).param("estado", s.estadoDestino()).param("suma", consume ? 1 : 0).param("motivo", s.motivo())
                .param("evidencia", s.idEvidencia()).param("ahora", fecha(ahora)).param("id", id).update();
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("idRepartidor", s.idRepartidor()); datos.put("idJornada", s.idJornada());
        if (s.idEvidencia() != null) datos.put("idEvidencia", s.idEvidencia());
        if (fallido) datos.put("consumeIntento", consume);
        historial(id, recojo.estado(), s.estadoDestino(), "F-03", "AUTOMATICO".equals(s.origen()) ? "SISTEMA" : "USUARIO", s.ejecutor(), s.origen(), s.motivo(), s.comentario(), datos, ahora);
        Hitos hitos = hitos(id);
        Recojo actual = buscar(id);
        proyectar(id, hitos, ahora);
        if (!"EN_CAMINO".equals(s.estadoDestino()) && !actual.esSimulado())
            eventoVentas(actual, recojo.estado(), fallido ? "RECOJO_FALLIDO" : "RECOJO_RECOGIDO", fallido ? Map.of("idRecojo", id.toString(), "motivo", s.motivo()) : Map.of("idRecojo", id.toString()), ahora);
        // Sin producto a bordo la capacidad se libera de inmediato; un RECOGIDO la conserva hasta la recepción.
        if (fallido) liberar(actual, "RECOJO_NO_REALIZADO");
        var respuesta = new RespuestaTransicion(id.toString(), recojo.estado(), s.estadoDestino(), actual.version(), actual.numeroIntentos(), s.motivo(), hitos, ahora);
        guardarClave("SERVICIO", actor, "TRANSICION_RECOJO", clave, huella, respuesta);
        return respuesta;
    }

    @Transactional
    public Recojo recibir(UUID id, UUID clave, int version, SolicitudRecepcion s, String actor, Instant ahora) {
        return comando(id, clave, version, "RECEPCION_RECOJO", s, actor, () -> {
            Recojo recojo = buscar(id);
            exigirEstado(recojo, "RECOGIDO");
            sql.sql("UPDATE recojos SET estado = 'RECIBIDO_EN_CENTRO', recibido_en_centro_en = :ahora, recibido_por = :actor, version = version + 1, actualizado_en = :ahora WHERE id = :id")
                    .param("ahora", fecha(ahora)).param("actor", actor).param("id", id).update();
            historial(id, "RECOGIDO", "RECIBIDO_EN_CENTRO", "F-04", "USUARIO", actor, "MANUAL", null, s == null ? null : s.observacion(), Map.of(), ahora);
            Recojo actual = buscar(id);
            proyectar(id, hitos(id), ahora);
            liberar(actual, "RECEPCION_EN_CENTRO");
            if (!actual.esSimulado()) eventoVentas(actual, "RECOGIDO", "RECOJO_RECIBIDO", Map.of("idRecojo", id.toString()), ahora);
            return actual;
        });
    }
    @Transactional
    public Recojo reprogramar(UUID id, UUID clave, int version, SolicitudReprogramacion s, String actor, Instant ahora) {
        return comando(id, clave, version, "REPROGRAMACION_RECOJO", s, actor, () -> {
            exigirEstado(buscar(id), "FALLIDO");
            // La jornada anterior conserva su copia FALLIDO: la nueva asignación la reemplaza con otra reserva.
            sql.sql("""
                    UPDATE recojos SET estado = 'PENDIENTE_ASIGNACION', fecha_programada = :fecha, repartidor_id_externo = NULL,
                        asignacion_diaria_id_externa = NULL, reserva_capacidad_id_externa = NULL, secuencia_ruta = NULL,
                        version = version + 1, actualizado_en = :ahora WHERE id = :id
                    """).param("fecha", s.fechaProgramada()).param("ahora", fecha(ahora)).param("id", id).update();
            historial(id, "FALLIDO", "PENDIENTE_ASIGNACION", "F-04", "USUARIO", actor, "MANUAL", null, s.observacion(),
                    Map.of("nuevaFechaProgramada", s.fechaProgramada().toString()), ahora);
            return buscar(id);
        });
    }
    @Transactional
    public Recojo cerrar(UUID id, UUID clave, int version, SolicitudCierre s, String actor, Instant ahora) {
        return comando(id, clave, version, "CIERRE_RECOJO", s, actor, () -> {
            exigirEstado(buscar(id), "FALLIDO");
            sql.sql("UPDATE recojos SET estado = 'CERRADO_SIN_RECOJO', motivo_cierre = :motivo, version = version + 1, actualizado_en = :ahora WHERE id = :id")
                    .param("motivo", s.motivoCierre()).param("ahora", fecha(ahora)).param("id", id).update();
            historial(id, "FALLIDO", "CERRADO_SIN_RECOJO", "F-04", "USUARIO", actor, "MANUAL", null, s.motivoCierre(), Map.of(), ahora);
            Recojo actual = buscar(id);
            proyectar(id, hitos(id), ahora);
            if (!actual.esSimulado()) eventoVentas(actual, "FALLIDO", "RECOJO_CERRADO", Map.of("idRecojo", id.toString(), "motivoCierre", s.motivoCierre()), ahora);
            return actual;
        });
    }

    private Recojo comando(UUID id, UUID clave, int version, String operacion, Object solicitud, String actor, Supplier<Recojo> ejecutar) {
        String huella = huella(Map.of("idRecojo", id.toString(), "version", version, "solicitud", solicitud == null ? Map.of() : solicitud));
        bloquear("recojo-clave:" + actor + ":" + clave);
        var repetida = respuestaPrevia("USUARIO", actor, operacion, clave.toString(), huella, Recojo.class);
        if (repetida.isPresent()) return repetida.get();
        bloquear("recojo:" + id);
        exigirVersion(buscar(id), version);
        Recojo resultado = ejecutar.get();
        guardarClave("USUARIO", actor, operacion, clave.toString(), huella, resultado);
        return resultado;
    }
    private <T> Optional<T> respuestaPrevia(String tipo, String actor, String operacion, String clave, String huella, Class<T> clase) {
        return sql.sql("""
                SELECT huella_solicitud, respuesta::text FROM claves_idempotencia
                WHERE actor_tipo = :tipo AND actor_id = :actor AND operacion = :operacion AND clave = :clave
                """).param("tipo", tipo).param("actor", actor).param("operacion", operacion).param("clave", clave).query((f, n) -> {
                    if (!huella.equals(f.getString(1))) throw conflicto("DESP_ERROR_OPERACION_REUTILIZADA", "La clave ya fue utilizada con otra solicitud");
                    return json.readValue(f.getString(2), clase);
                }).optional();
    }
    private void guardarClave(String tipo, String actor, String operacion, String clave, String huella, Object respuesta) {
        sql.sql("""
                INSERT INTO claves_idempotencia(clave, actor_tipo, actor_id, operacion, huella_solicitud, estado, codigo_respuesta, respuesta)
                VALUES (:clave, :tipo, :actor, :operacion, :huella, 'COMPLETADA', 200, CAST(:respuesta AS jsonb))
                """).param("clave", clave).param("tipo", tipo).param("actor", actor).param("operacion", operacion).param("huella", huella)
                .param("respuesta", json.writeValueAsString(respuesta)).update();
    }
    private boolean validarMotivo(String motivo, boolean noIntentado) {
        var fila = sql.sql("SELECT seleccionable_en_campo, consume_intento, activo, ambito FROM motivos_fallo WHERE codigo = :codigo").param("codigo", motivo)
                .query((f, n) -> new Object[] {f.getBoolean(1), f.getBoolean(2), f.getBoolean(3), f.getString(4)}).optional()
                .orElseThrow(() -> ErrorDeNegocio.de(400, "DESP_ERROR_MOTIVO_INVALIDO", "El motivo no existe en el catálogo"));
        // NO_INTENTADO solo llega desde el cierre de jornada; los demás deben poder elegirse en campo para un recojo.
        if ("ENTREGA".equals(fila[3]) || !noIntentado && (!(boolean) fila[0] || !(boolean) fila[2]))
            throw ErrorDeNegocio.de(400, "DESP_ERROR_MOTIVO_INVALIDO", "El motivo no se puede registrar en un recojo");
        return (boolean) fila[1];
    }
    private Hitos hitos(UUID id) {
        return sql.sql("""
                WITH ciclo AS (SELECT max(registrado_en) AS desde FROM historial_estados_recojo WHERE recojo_id = :id AND estado_nuevo = 'ASIGNADO')
                SELECT c.desde,
                    max(h.registrado_en) FILTER (WHERE h.estado_nuevo = 'EN_CAMINO') AS en_camino,
                    max(h.registrado_en) FILTER (WHERE h.estado_nuevo = 'RECOGIDO') AS recogido,
                    max(h.registrado_en) FILTER (WHERE h.estado_nuevo = 'FALLIDO') AS fallido
                FROM ciclo c LEFT JOIN historial_estados_recojo h ON h.recojo_id = :id AND h.registrado_en >= c.desde
                GROUP BY c.desde
                """).param("id", id).query((f, n) -> new Hitos(instante(f.getObject(1, OffsetDateTime.class)), instante(f.getObject(2, OffsetDateTime.class)),
                        instante(f.getObject(3, OffsetDateTime.class)), instante(f.getObject(4, OffsetDateTime.class)))).single();
    }
    private void proyectar(UUID id, Hitos hitos, Instant ahora) {
        var fila = sql.sql("SELECT r.* FROM recojos r WHERE r.id = :id").param("id", id).query((f, n) -> {
            Map<String, Object> proyeccion = new LinkedHashMap<>();
            proyeccion.put("idRecojo", id.toString()); proyeccion.put("idDespacho", f.getString("despacho_id"));
            proyeccion.put("codigoRastreoInterno", f.getString("codigo_rastreo_interno")); proyeccion.put("estado", f.getString("estado"));
            proyeccion.put("idJornada", f.getString("asignacion_diaria_id_externa")); proyeccion.put("idReserva", f.getString("reserva_capacidad_id_externa"));
            proyeccion.put("secuenciaRuta", f.getObject("secuencia_ruta", Integer.class));
            proyeccion.put("destinatario", new Destinatario(f.getString("contacto_nombre"), f.getString("contacto_telefono")));
            proyeccion.put("destino", new Destino(f.getString("direccion"), f.getString("referencia"), f.getString("departamento"), f.getString("provincia"),
                    f.getString("distrito"), f.getString("codigo_postal"), null));
            proyeccion.put("lineas", json.readValue(f.getString("lineas"), LINEAS));
            proyeccion.put("fechaProgramada", f.getObject("fecha_programada", LocalDate.class)); proyeccion.put("numeroIntentos", f.getInt("numero_intentos"));
            proyeccion.put("version", f.getInt("version")); proyeccion.put("actualizadoEn", ahora);
            proyeccion.put("motivoFallo", f.getString("motivo_codigo")); proyeccion.put("hitos", hitos);
            return proyeccion;
        }).single();
        evento(id, UUID.fromString((String) fila.get("idDespacho")), "OPERACION", "PROYECCION_RECOJO", fila);
    }
    private void liberar(Recojo recojo, String motivo) {
        String reserva = sql.sql("SELECT reserva_capacidad_id_externa FROM recojos WHERE id = CAST(:id AS uuid)").param("id", recojo.idRecojo()).query(String.class).single();
        evento(UUID.fromString(recojo.idRecojo()), UUID.fromString(recojo.idDespacho()), "OPERACION_LIBERACION", "LIBERACION_CAPACIDAD",
                Map.of("idReserva", reserva, "idDespacho", recojo.idDespacho(), "motivo", motivo));
    }
    private void eventoVentas(Recojo recojo, String anterior, String tipo, Map<String, Object> datos, Instant ahora) {
        UUID evento = UUID.randomUUID();
        var idPedido = sql.sql("SELECT pedido_id FROM despachos WHERE id = CAST(:id AS uuid)").param("id", recojo.idDespacho()).query(String.class).single();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idEvento", evento.toString()); payload.put("tipo", tipo); payload.put("idPedido", idPedido);
        payload.put("idDespacho", recojo.idDespacho()); payload.put("estadoAnterior", anterior); payload.put("estadoNuevo", recojo.estado());
        payload.put("ocurridoEn", ahora); payload.put("datos", datos);
        sql.sql("INSERT INTO eventos_salientes(id, despacho_id, recojo_id, destino, tipo_evento, payload) VALUES (:id, :despacho, :recojo, 'VENTAS', :tipo, CAST(:payload AS jsonb))")
                .param("id", evento).param("despacho", UUID.fromString(recojo.idDespacho())).param("recojo", UUID.fromString(recojo.idRecojo())).param("tipo", tipo)
                .param("payload", json.writeValueAsString(payload)).update();
    }
    private void evento(UUID recojo, UUID despacho, String destino, String tipo, Object payload) {
        sql.sql("INSERT INTO eventos_salientes(id, despacho_id, recojo_id, destino, tipo_evento, payload) VALUES (:id, :despacho, :recojo, :destino, :tipo, CAST(:payload AS jsonb))")
                .param("id", UUID.randomUUID()).param("despacho", despacho).param("recojo", recojo).param("destino", destino).param("tipo", tipo)
                .param("payload", json.writeValueAsString(payload)).update();
    }
    private void historial(UUID id, String anterior, String nuevo, String funcionalidad, String tipo, String actor, String origen, String motivo, String observacion, Object datos, Instant ahora) {
        sql.sql("""
                INSERT INTO historial_estados_recojo(recojo_id, estado_anterior, estado_nuevo, funcionalidad_origen, ejecutor_tipo, ejecutor_id,
                    origen, motivo_codigo, observaciones, datos, registrado_en)
                VALUES (:id, :anterior, :nuevo, :funcionalidad, :tipo, :actor, :origen, :motivo, :observacion, CAST(:datos AS jsonb), :ahora)
                """).param("id", id).param("anterior", anterior).param("nuevo", nuevo).param("funcionalidad", funcionalidad).param("tipo", tipo)
                .param("actor", actor).param("origen", origen).param("motivo", motivo).param("observacion", observacion)
                .param("datos", json.writeValueAsString(datos)).param("ahora", fecha(ahora)).update();
    }
    private Recojo buscar(UUID id) {
        return sql.sql(VISTA + " WHERE r.id = :id").param("id", id).query(this::recojo).optional()
                .orElseThrow(() -> ErrorDeNegocio.de(404, "DESP_ERROR_RECOJO_NO_ENCONTRADO", "El recojo no existe"));
    }
    private static void exigirEstado(Recojo recojo, String esperado) {
        if (!esperado.equals(recojo.estado())) throw conflicto("DESP_ERROR_ESTADO_INCOMPATIBLE", "El recojo ya no admite esta acción").conEstadoActual(recojo.estado());
    }
    private static void exigirVersion(Recojo recojo, int version) {
        if (recojo.version() != version) throw conflicto("DESP_ERROR_RECOJO_DESACTUALIZADO", "El recojo cambió; vuelve a consultar").conEstadoActual(recojo.estado());
    }
    private Recojo recojo(ResultSet f, int n) throws SQLException {
        var recibido = f.getObject("recibido_en_centro_en", OffsetDateTime.class);
        return new Recojo(f.getString("id"), f.getString("despacho_id"), f.getString("codigo_rastreo_interno"), f.getString("estado"), f.getBoolean("es_simulado"),
                f.getString("contacto_nombre"), f.getString("contacto_telefono"), f.getString("direccion"), f.getString("referencia"), f.getString("distrito"),
                f.getString("zona_id"), json.readValue(f.getString("lineas"), LINEAS), f.getBigDecimal("peso_kg"), f.getBigDecimal("volumen_m3"), f.getInt("cantidad_paquetes"),
                f.getObject("fecha_programada", LocalDate.class), f.getString("repartidor_id_externo"), f.getString("asignacion_diaria_id_externa"),
                f.getObject("secuencia_ruta", Integer.class), f.getInt("numero_intentos"), f.getString("motivo_codigo"), f.getString("etiqueta_motivo"),
                f.getString("evidencia_id_externa"), f.getString("motivo_cierre"), recibido == null ? null : recibido.toInstant(), f.getInt("version"),
                f.getObject("actualizado_en", OffsetDateTime.class).toInstant());
    }
    private Intencion intencion(ResultSet f, int n) throws SQLException {
        String respuesta = f.getString("respuesta");
        return new Intencion(f.getObject("id", UUID.class), f.getObject("recojo_id", UUID.class), f.getObject("despacho_id", UUID.class), f.getObject("clave", UUID.class),
                f.getInt("version_origen"), f.getString("ejecutor_id"), f.getString("id_repartidor"), f.getString("id_jornada"), f.getString("estado"), f.getString("id_reserva"),
                respuesta == null ? null : json.readValue(respuesta, Recojo.class), f.getBigDecimal("peso_kg"), f.getBigDecimal("volumen_m3"), f.getInt("cantidad_paquetes"));
    }
    private String huella(Object contenido) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(contenido).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private void bloquear(String clave) { sql.sql("SELECT pg_advisory_xact_lock(hashtextextended(:clave, 0))").param("clave", clave).query((f, n) -> true).single(); }
    private static OffsetDateTime fecha(Instant instante) { return instante.atOffset(ZoneOffset.UTC); }
    private static Instant instante(OffsetDateTime valor) { return valor == null ? null : valor.toInstant(); }
    private static ErrorDeNegocio conflicto(String codigo, String mensaje) { return ErrorDeNegocio.de(409, codigo, mensaje); }
}
