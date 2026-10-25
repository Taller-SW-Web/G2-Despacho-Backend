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
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

@Repository
@Profile("!integracion-local")
public class CierreDespachoJdbc {
    private final JdbcClient sql;
    private final JsonMapper json;
    private final Clock reloj;
    private final HistorialIncidenciasJdbc historial;
    private final TransactionTemplate transaccion;

    public CierreDespachoJdbc(JdbcClient sql, JsonMapper json, Clock reloj,
            HistorialIncidenciasJdbc historial, PlatformTransactionManager gestor) {
        this.sql = sql; this.json = json; this.reloj = reloj; this.historial = historial;
        this.transaccion = new TransactionTemplate(gestor);
    }
    public ResultadoCierre cerrar(String despacho, SolicitudCierre solicitud, String actor) {
        return transaccion.execute(estado -> registrar(despacho, solicitud, actor));
    }
    private ResultadoCierre registrar(String despacho, SolicitudCierre solicitud, String actor) {
        // El bloqueo canónico serializa cierre/recepción/reprogramación; los demás productores comparan versión.
        var actual = sql.sql("""
                SELECT estado, version, ultima_incidencia_id::text AS incidencia, numero_intentos, pedido_id, es_simulado
                FROM despachos WHERE id::text = :id FOR UPDATE
                """).param("id", despacho).query((f, n) -> new Actual(f.getString("estado"), f.getInt("version"),
                        f.getString("incidencia"), f.getInt("numero_intentos"), f.getString("pedido_id"), f.getBoolean("es_simulado")))
                .optional().orElseThrow(() -> ErrorDeNegocio.de(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe"));
        String huella = huella(json.writeValueAsString(solicitud));
        var anterior = sql.sql("""
                SELECT ejecutor_id, datos::text, observaciones, registrado_en FROM historial_estados_despacho
                WHERE despacho_id::text = :despacho AND tipo_operacion = 'TRANSICION' AND estado_nuevo = 'DEVUELTO_A_ORIGEN'
                  AND datos->>'idIncidencia' = :incidencia
                  AND datos->'controlCierre'->>'versionSolicitada' = :version
                """).param("despacho", despacho).param("incidencia", solicitud.idIncidencia())
                .param("version", Integer.toString(solicitud.versionDespacho()))
                .query((f, n) -> new Aceptada(f.getString(1), f.getString(2), f.getString(3),
                        f.getObject(4, OffsetDateTime.class).toInstant())).optional();
        if (anterior.isPresent()) {
            var aceptada = anterior.get();
            var datos = json.readTree(aceptada.datos());
            var control = datos.path("controlCierre");
            if (!actor.equalsIgnoreCase(aceptada.actor()) || !huella.equals(control.path("hashContenido").asString()))
                throw conflicto("DESP_ERROR_OPERACION_REUTILIZADA", "La operación ya fue aceptada con otro contenido o ejecutor");
            var guardada = control.path("respuestaOriginal");
            for (String campo : List.of("idDespacho", "idIncidencia", "estado", "versionDespacho", "motivo",
                    "observacion", "numeroIntentos", "cerradoEn", "idEvento"))
                if (guardada.get(campo) == null) throw inconsistencia();
            ResultadoCierre original;
            try { original = json.readValue(guardada.toString(), ResultadoCierre.class); }
            catch (RuntimeException error) { throw inconsistencia(); }
            if (control.path("codigoRespuesta").asInt() != 200 || original == null
                    || !despacho.equals(original.idDespacho()) || !solicitud.idIncidencia().equals(original.idIncidencia())
                    || !"DEVUELTO_A_ORIGEN".equals(original.estado()) || original.versionDespacho() != solicitud.versionDespacho() + 1
                    || !solicitud.motivo().equals(original.motivo()) || !solicitud.motivo().equals(datos.path("motivoCierre").asString())
                    || !Objects.equals(solicitud.observacion(), original.observacion())
                    || !Objects.equals(aceptada.observacion(), original.observacion()) || !aceptada.creada().equals(original.cerradoEn())
                    || original.numeroIntentos() < 0 || (original.idEvento() == null) != actual.simulado()) throw inconsistencia();
            return original;
        }
        if (actual.version() != solicitud.versionDespacho() || !Objects.equals(actual.incidencia(), solicitud.idIncidencia()))
            throw conflicto("DESP_ERROR_DESPACHO_DESACTUALIZADO", "La incidencia o versión cambió; vuelve a consultar");
        if (!"FALLIDO".equals(actual.estado())) throw conflicto("DESP_ERROR_ESTADO_INCOMPATIBLE", "El despacho no está fallido");
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
                        f.getString(10), f.getObject(11) != null, f.getBoolean(12))).optional().orElseThrow(CierreDespachoJdbc::inconsistencia);
        if (!salida.recibida()) throw conflicto("DESP_ERROR_RECEPCION_PENDIENTE", "Primero confirma la recepción del paquete");
        if (actual.intentos() < 0 || vacio(actual.pedido()) || actual.version() == Integer.MAX_VALUE
                || !salida.actor() || !salida.sello() || vacio(salida.reserva()) || vacio(salida.jornada())
                || vacio(salida.repartidor()) || salida.liberacion() == null
                || !Objects.equals(salida.reserva(), salida.reservaLiberacion()) || !Objects.equals(salida.jornada(), salida.jornadaLiberacion())
                || !Set.of("PENDIENTE", "CONFIRMADA").contains(salida.estadoLiberacion())
                || "CONFIRMADA".equals(salida.estadoLiberacion()) != salida.confirmada() || salida.confirmada() && salida.revision()) throw inconsistencia();
        Instant ahora = reloj.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var instanteSql = OffsetDateTime.ofInstant(ahora, ZoneOffset.UTC);
        int cambiados = sql.sql("""
                UPDATE despachos SET estado = 'DEVUELTO_A_ORIGEN', version = version + 1, actualizado_en = :ahora
                WHERE id::text = :id AND version = :version AND ultima_incidencia_id::text = :incidencia
                """).param("ahora", instanteSql).param("id", despacho).param("version", solicitud.versionDespacho())
                .param("incidencia", solicitud.idIncidencia()).update();
        if (cambiados != 1) throw conflicto("DESP_ERROR_DESPACHO_DESACTUALIZADO", "La versión cambió durante la decisión");
        UUID evento = actual.simulado() ? null : UUID.randomUUID();
        var respuesta = new ResultadoCierre(despacho, solicitud.idIncidencia(), "DEVUELTO_A_ORIGEN", actual.version() + 1,
                solicitud.motivo(), solicitud.observacion(), actual.intentos(), ahora, evento == null ? null : evento.toString());
        historial.registrar(despacho, "TRANSICION", "FALLIDO", "DEVUELTO_A_ORIGEN", actor, solicitud.observacion(),
                Map.of("idIncidencia", solicitud.idIncidencia(), "motivoCierre", solicitud.motivo(), "controlCierre",
                        Map.of("versionSolicitada", solicitud.versionDespacho(), "hashContenido", huella,
                                "codigoRespuesta", 200, "respuestaOriginal", respuesta)), ahora);
        if (evento != null) {
            var contenido = Map.of("idEvento", evento.toString(), "tipo", "DESPACHO_DEVUELTO_A_ORIGEN",
                    "idPedido", actual.pedido(), "idDespacho", despacho, "estadoAnterior", "FALLIDO",
                    "estadoNuevo", "DEVUELTO_A_ORIGEN", "ocurridoEn", ahora, "datos", Map.of("motivoCierre", solicitud.motivo()));
            sql.sql("""
                    INSERT INTO eventos_salientes(id, despacho_id, destino, tipo_evento, payload, estado_envio, proximo_intento_en, creado_en)
                    VALUES (:id, CAST(:despacho AS uuid), 'VENTAS', 'DESPACHO_DEVUELTO_A_ORIGEN', CAST(:payload AS jsonb), 'PENDIENTE', :ahora, :ahora)
                    """).param("id", evento).param("despacho", despacho).param("payload", json.writeValueAsString(contenido))
                    .param("ahora", instanteSql).update();
        }
        return respuesta;
    }
    private static boolean vacio(String texto) { return texto == null || texto.isBlank(); }
    private static String huella(String contenido) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static ErrorDeNegocio conflicto(String codigo, String mensaje) { return ErrorDeNegocio.de(409, codigo, mensaje); }
    private static ErrorDeNegocio inconsistencia() {
        return ErrorDeNegocio.de(500, "DESP_ERROR_DATOS_INCONSISTENTES", "Las referencias de la incidencia o del cierre son inconsistentes");
    }
    private record Actual(String estado, int version, String incidencia, int intentos, String pedido, boolean simulado) {}
    private record Aceptada(String actor, String datos, String observacion, Instant creada) {}
    private record Salida(boolean recibida, boolean actor, boolean sello, String reserva, String jornada, String repartidor,
            String liberacion, String reservaLiberacion, String jornadaLiberacion, String estadoLiberacion, boolean confirmada, boolean revision) {}
}
