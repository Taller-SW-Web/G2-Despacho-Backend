package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api.SolicitudesReprogramacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

@Repository
@Profile("!integracion-local")
public class ReprogramacionDespachoJdbc {
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private final JdbcClient sql;
    private final JsonMapper json;
    private final Clock reloj;
    private final HistorialIncidenciasJdbc historial;
    private final TransactionTemplate transaccion;

    public ReprogramacionDespachoJdbc(JdbcClient sql, JsonMapper json, Clock reloj,
            HistorialIncidenciasJdbc historial, PlatformTransactionManager gestor) {
        this.sql = sql; this.json = json; this.reloj = reloj; this.historial = historial;
        this.transaccion = new TransactionTemplate(gestor);
    }
    public ResultadoReprogramacion reprogramar(String despacho, SolicitudReprogramacion solicitud, String actor) {
        return transaccion.execute(estado -> registrar(despacho, solicitud, actor));
    }
    private ResultadoReprogramacion registrar(String despacho, SolicitudReprogramacion solicitud, String actor) {
        // Mismo bloqueo canónico que recepción; otros productores deben comparar su versión al escribir.
        var actual = sql.sql("""
                SELECT estado, version, ultima_incidencia_id::text AS incidencia, numero_intentos,
                  pedido_anulado_en IS NOT NULL AS anulado, pedido_id, es_simulado
                FROM despachos WHERE id::text = :id FOR UPDATE
                """).param("id", despacho).query((f, n) -> new Actual(f.getString("estado"), f.getInt("version"),
                        f.getString("incidencia"), f.getInt("numero_intentos"), f.getBoolean("anulado"),
                        f.getString("pedido_id"), f.getBoolean("es_simulado"))).optional()
                .orElseThrow(() -> ErrorDeNegocio.de(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe"));
        String huella = huella(json.writeValueAsString(solicitud));
        var anterior = sql.sql("""
                SELECT ejecutor_id::text, hash_contenido, respuesta_original::text, fecha_programada, observacion,
                  creado_en, evento_id::text FROM reprogramaciones_despacho
                WHERE despacho_id::text = :despacho AND incidencia_id::text = :incidencia AND version_solicitada = :version
                """).param("despacho", despacho).param("incidencia", solicitud.idIncidencia()).param("version", solicitud.versionDespacho())
                .query((f, n) -> new Aceptada(f.getString(1), f.getString(2), f.getString(3), f.getObject(4, LocalDate.class),
                        f.getString(5), f.getObject(6, OffsetDateTime.class).toInstant(), f.getString(7))).optional();
        if (anterior.isPresent()) {
            var aceptada = anterior.get();
            if (!actor.equalsIgnoreCase(aceptada.actor()) || !huella.equals(aceptada.huella()))
                throw conflicto("DESP_ERROR_OPERACION_REUTILIZADA", "La operación ya fue aceptada con otro contenido o ejecutor");
            return recuperar(aceptada, despacho, solicitud);
        }
        if (actual.version() != solicitud.versionDespacho() || !Objects.equals(actual.incidencia(), solicitud.idIncidencia()))
            throw conflicto("DESP_ERROR_DESPACHO_DESACTUALIZADO", "La incidencia o versión cambió; vuelve a consultar");
        if (!"FALLIDO".equals(actual.estado()))
            throw conflicto("DESP_ERROR_ESTADO_INCOMPATIBLE", "El despacho no está fallido");
        var salida = sql.sql("""
                SELECT i.recibido_en_centro_en, i.recibido_por, i.sello_intacto,
                  i.reserva_capacidad_id_externa, i.asignacion_diaria_id_externa, i.repartidor_id_externo,
                  l.id::text, l.reserva_id_externa, l.jornada_id_externa, l.estado, l.confirmada_en, l.requiere_revision
                FROM intentos_entrega i LEFT JOIN liberaciones_capacidad l
                  ON l.incidencia_id = i.id AND l.despacho_id = i.despacho_id
                WHERE i.id::text = :incidencia AND i.despacho_id::text = :despacho AND i.resultado = 'FALLIDO'
                FOR UPDATE OF i
                """).param("incidencia", solicitud.idIncidencia()).param("despacho", despacho)
                .query((f, n) -> new Salida(f.getObject(1) != null, f.getObject(2) != null, f.getObject(3) != null,
                        f.getString(4), f.getString(5), f.getString(6), f.getString(7), f.getString(8), f.getString(9),
                        f.getString(10), f.getObject(11) != null, f.getBoolean(12))).optional()
                .orElseThrow(ReprogramacionDespachoJdbc::inconsistencia);
        if (!salida.recibida()) throw conflicto("DESP_ERROR_RECEPCION_PENDIENTE", "Primero confirma la recepción del paquete");
        if (actual.anulado()) throw conflicto("DESP_ERROR_PEDIDO_ANULADO", "El pedido está anulado; corresponde cierre a origen");
        int maximo = maximoVigente();
        if (actual.intentos() < 0 || vacio(actual.pedido()) || actual.version() == Integer.MAX_VALUE
                || !salida.actor() || !salida.sello() || vacio(salida.reserva()) || vacio(salida.jornada())
                || vacio(salida.repartidor()) || salida.liberacion() == null
                || !Objects.equals(salida.reserva(), salida.reservaLiberacion())
                || !Objects.equals(salida.jornada(), salida.jornadaLiberacion())
                || !Set.of("PENDIENTE", "CONFIRMADA").contains(salida.estadoLiberacion())
                || "CONFIRMADA".equals(salida.estadoLiberacion()) != salida.confirmada()
                || salida.confirmada() && salida.revision()) throw inconsistencia();
        if (actual.intentos() >= maximo)
            throw ErrorDeNegocio.de(422, "DESP_ERROR_MAXIMO_INTENTOS_ALCANZADO", "El despacho alcanzó el máximo vigente de intentos");
        // PostgreSQL conserva microsegundos; respuesta original y columnas deben usar la misma precisión.
        Instant ahora = reloj.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!solicitud.nuevaFechaProgramada().isAfter(LocalDate.ofInstant(ahora, LIMA)))
            throw SolicitudesReprogramacion.invalida("nuevaFechaProgramada", "Elige una fecha posterior a hoy en Lima");
        var instanteSql = OffsetDateTime.ofInstant(ahora, ZoneOffset.UTC);
        int cambiados = sql.sql("""
                UPDATE despachos SET estado = 'PENDIENTE_ASIGNACION', fecha_programada = :fecha,
                  en_cola_desde = :ahora, version = version + 1, actualizado_en = :ahora,
                  repartidor_id_externo = NULL, asignacion_diaria_id_externa = NULL,
                  reserva_capacidad_id_externa = NULL, secuencia_ruta = NULL
                WHERE id::text = :id AND version = :version AND ultima_incidencia_id::text = :incidencia
                """).param("fecha", solicitud.nuevaFechaProgramada()).param("ahora", instanteSql).param("id", despacho)
                .param("version", solicitud.versionDespacho()).param("incidencia", solicitud.idIncidencia()).update();
        if (cambiados != 1) throw conflicto("DESP_ERROR_DESPACHO_DESACTUALIZADO", "La versión cambió durante la decisión");
        UUID idHistorial = historial.registrar(despacho, "TRANSICION", "FALLIDO", "PENDIENTE_ASIGNACION", actor,
                solicitud.observacion(), Map.of("idIncidencia", solicitud.idIncidencia(),
                        "nuevaFechaProgramada", solicitud.nuevaFechaProgramada().toString()), ahora);
        UUID evento = actual.simulado() ? null : UUID.randomUUID();
        if (evento != null) {
            var contenido = Map.of("idEvento", evento.toString(), "tipo", "DESPACHO_REPROGRAMADO",
                    "idPedido", actual.pedido(), "idDespacho", despacho, "estadoAnterior", "FALLIDO",
                    "estadoNuevo", "PENDIENTE_ASIGNACION", "ocurridoEn", ahora,
                    "datos", Map.of("nuevaFechaProgramada", solicitud.nuevaFechaProgramada()));
            sql.sql("""
                    INSERT INTO eventos_salientes(id, despacho_id, destino, tipo_evento, payload, estado_envio, proximo_intento_en, creado_en)
                    VALUES (:id, CAST(:despacho AS uuid), 'VENTAS', 'DESPACHO_REPROGRAMADO', CAST(:payload AS jsonb), 'PENDIENTE', :ahora, :ahora)
                    """).param("id", evento).param("despacho", despacho).param("payload", json.writeValueAsString(contenido))
                    .param("ahora", instanteSql).update();
        }
        var respuesta = new ResultadoReprogramacion(despacho, solicitud.idIncidencia(), "PENDIENTE_ASIGNACION",
                actual.version() + 1, solicitud.nuevaFechaProgramada(), actual.intentos(), solicitud.observacion(),
                ahora, evento == null ? null : evento.toString());
        sql.sql("""
                INSERT INTO reprogramaciones_despacho(id, despacho_id, incidencia_id, version_solicitada, ejecutor_id,
                  hash_contenido, fecha_programada, observacion, historial_id, evento_id, codigo_respuesta, respuesta_original, creado_en)
                VALUES (:id, CAST(:despacho AS uuid), CAST(:incidencia AS uuid), :version, CAST(:actor AS uuid),
                  :huella, :fecha, :observacion, :historial, :evento, 200, CAST(:respuesta AS jsonb), :ahora)
                """).param("id", UUID.randomUUID()).param("despacho", despacho).param("incidencia", solicitud.idIncidencia())
                .param("version", solicitud.versionDespacho()).param("actor", actor).param("huella", huella)
                .param("fecha", solicitud.nuevaFechaProgramada()).param("observacion", solicitud.observacion())
                .param("historial", idHistorial).param("evento", evento).param("respuesta", json.writeValueAsString(respuesta))
                .param("ahora", instanteSql).update();
        return respuesta;
    }
    private int maximoVigente() {
        String valor = sql.sql("SELECT valor FROM parametros_configuracion WHERE clave = 'MAXIMO_INTENTOS' FOR SHARE")
                .query(String.class).optional().orElse(null);
        try { int maximo = Integer.parseInt(valor); if (maximo > 0) return maximo; }
        catch (RuntimeException error) { /* Se informa inconsistencia; no se inventa el valor inicial. */ }
        throw inconsistencia();
    }
    private ResultadoReprogramacion recuperar(Aceptada aceptada, String despacho, SolicitudReprogramacion solicitud) {
        ResultadoReprogramacion original;
        try { original = json.readValue(aceptada.respuesta(), ResultadoReprogramacion.class); }
        catch (RuntimeException error) { throw inconsistencia(); }
        if (original == null || !despacho.equals(original.idDespacho()) || !solicitud.idIncidencia().equals(original.idIncidencia())
                || !"PENDIENTE_ASIGNACION".equals(original.estado()) || original.versionDespacho() != solicitud.versionDespacho() + 1
                || !solicitud.nuevaFechaProgramada().equals(original.fechaProgramada()) || !aceptada.fecha().equals(original.fechaProgramada())
                || !Objects.equals(solicitud.observacion(), original.observacion()) || !Objects.equals(aceptada.observacion(), original.observacion())
                || !aceptada.creada().equals(original.reprogramadoEn()) || original.numeroIntentos() < 0
                || !Objects.equals(aceptada.evento(), original.idEvento())) throw inconsistencia();
        return original;
    }
    private static boolean vacio(String texto) { return texto == null || texto.isBlank(); }
    private static String huella(String contenido) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static ErrorDeNegocio conflicto(String codigo, String mensaje) { return ErrorDeNegocio.de(409, codigo, mensaje); }
    private static ErrorDeNegocio inconsistencia() {
        return ErrorDeNegocio.de(500, "DESP_ERROR_DATOS_INCONSISTENTES", "La política o referencias de la incidencia son inconsistentes");
    }
    private record Actual(String estado, int version, String incidencia, int intentos, boolean anulado, String pedido, boolean simulado) {}
    private record Aceptada(String actor, String huella, String respuesta, LocalDate fecha, String observacion, Instant creada, String evento) {}
    private record Salida(boolean recibida, boolean actor, boolean sello, String reserva, String jornada, String repartidor,
            String liberacion, String reservaLiberacion, String jornadaLiberacion, String estadoLiberacion, boolean confirmada, boolean revision) {}
}
