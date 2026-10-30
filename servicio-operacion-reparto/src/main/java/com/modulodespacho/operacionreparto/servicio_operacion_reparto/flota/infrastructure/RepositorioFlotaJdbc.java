package com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.application.IRepositorioFlota;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.ConflictoFlota;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.DatosFlota.*;

@Repository
@Profile("!integracion-local")
public class RepositorioFlotaJdbc implements IRepositorioFlota {
    private static final RowMapper<Repartidor> MAPEAR_REPARTIDOR = (fila, numero) -> new Repartidor(
            uuid(fila, "id"), fila.getString("nombres"), fila.getString("apellidos"), fila.getString("dni"),
            fila.getString("telefono"), fila.getString("correo"), fila.getString("numero_brevete"),
            Turno.valueOf(fila.getString("turno_habitual")), EstadoRegistro.valueOf(fila.getString("estado_registro")),
            Vinculacion.valueOf(fila.getString("estado_vinculacion")), uuid(fila, "id_usuario_seguridad"));
    private static final RowMapper<Furgoneta> MAPEAR_FURGONETA = (fila, numero) -> new Furgoneta(
            uuid(fila, "id"), fila.getString("placa"), EstadoFurgoneta.desdePersistido(fila.getString("estado")),
            fila.getBigDecimal("capacidad_max_kg"), fila.getBigDecimal("capacidad_max_m3"), fila.getInt("max_paquetes"));
    private final JdbcClient sql;
    public RepositorioFlotaJdbc(JdbcClient sql) { this.sql = sql; }

    public Pagina<Repartidor> listarRepartidores(int pagina, int tamano, EstadoRegistro registro, Vinculacion vinculacion, Turno turno) {
        String filtro = " WHERE (:registro = '' OR estado_registro = :registro)"
                + " AND (:vinculacion = '' OR estado_vinculacion = :vinculacion)"
                + " AND (:turno = '' OR turno_habitual = :turno)";
        var parametros = java.util.Map.of("registro", nombre(registro), "vinculacion", nombre(vinculacion), "turno", nombre(turno));
        long total = sql.sql("SELECT count(*) FROM perfiles_flota" + filtro).params(parametros).query(Long.class).single();
        var contenido = sql.sql("SELECT * FROM perfiles_flota" + filtro + " ORDER BY creado_en DESC, id LIMIT :tamano OFFSET :inicio")
                .params(parametros).param("tamano", tamano).param("inicio", (long) (pagina - 1) * tamano).query(MAPEAR_REPARTIDOR).list();
        return new Pagina<>(contenido, pagina, tamano, total);
    }
    public Pagina<Furgoneta> listarFurgonetas(int pagina, int tamano, EstadoFurgoneta estado, String placa) {
        // strpos evita que los comodines del usuario alteren la búsqueda literal por placa.
        String filtro = " WHERE (:estado = '' OR estado = :estado) AND (:placa = '' OR strpos(upper(placa), :placa) > 0)";
        var parametros = java.util.Map.of("estado", estado == null ? "" : estado.persistido(), "placa", placa == null ? "" : placa);
        long total = sql.sql("SELECT count(*) FROM furgonetas" + filtro).params(parametros).query(Long.class).single();
        var contenido = sql.sql("SELECT * FROM furgonetas" + filtro + " ORDER BY creado_en DESC, id LIMIT :tamano OFFSET :inicio")
                .params(parametros).param("tamano", tamano).param("inicio", (long) (pagina - 1) * tamano).query(MAPEAR_FURGONETA).list();
        return new Pagina<>(contenido, pagina, tamano, total);
    }
    public Repartidor obtenerRepartidor(UUID id, boolean bloquear) {
        return sql.sql("SELECT * FROM perfiles_flota WHERE id = :id" + (bloquear ? " FOR UPDATE" : ""))
                .param("id", id).query(MAPEAR_REPARTIDOR).optional().orElseThrow(ConflictoFlota::inexistente);
    }
    public Furgoneta obtenerFurgoneta(UUID id, boolean bloquear) {
        return sql.sql("SELECT * FROM furgonetas WHERE id = :id" + (bloquear ? " FOR UPDATE" : ""))
                .param("id", id).query(MAPEAR_FURGONETA).optional().orElseThrow(ConflictoFlota::inexistente);
    }
    public Repartidor registrarRepartidor(AltaRepartidor datos, UUID actor) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                INSERT INTO perfiles_flota (id, nombres, apellidos, dni, telefono, correo, numero_brevete,
                    turno_habitual, estado_registro, estado_vinculacion, creado_por, actualizado_por, creado_en, actualizado_en)
                VALUES (:id, :nombres, :apellidos, :dni, :telefono, :correo, :breve,
                    :turno, 'ACTIVO', 'PENDIENTE', :actor, :actor, now(), now())
                """).param("id", id).param("nombres", datos.nombres()).param("apellidos", datos.apellidos())
                .param("dni", datos.dni()).param("telefono", datos.contacto().telefono()).param("correo", datos.contacto().correo())
                .param("breve", datos.contacto().numeroBrevete()).param("turno", datos.contacto().turnoHabitual().name())
                .param("actor", actor).update();
        return obtenerRepartidor(id, false);
    }
    public Repartidor editarRepartidor(UUID id, Contacto datos, UUID actor) {
        sql.sql("""
                UPDATE perfiles_flota SET telefono = :telefono, correo = :correo, numero_brevete = :breve,
                    turno_habitual = :turno, actualizado_por = :actor, actualizado_en = now() WHERE id = :id
                """).param("id", id).param("telefono", datos.telefono()).param("correo", datos.correo())
                .param("breve", datos.numeroBrevete()).param("turno", datos.turnoHabitual().name()).param("actor", actor).update();
        return obtenerRepartidor(id, false);
    }
    public Furgoneta registrarFurgoneta(String placa, Capacidad datos, UUID actor) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                INSERT INTO furgonetas (id, placa, estado, capacidad_max_kg, capacidad_max_m3, max_paquetes,
                    creado_por, actualizado_por, creado_en, actualizado_en)
                VALUES (:id, :placa, 'DISPONIBLE', :peso, :volumen, :paquetes, :actor, :actor, now(), now())
                """).param("id", id).param("placa", placa).param("peso", datos.capacidadPesoKg())
                .param("volumen", datos.capacidadVolumenM3()).param("paquetes", datos.maximoPaquetes()).param("actor", actor).update();
        return obtenerFurgoneta(id, false);
    }
    public Furgoneta editarFurgoneta(UUID id, Capacidad datos, UUID actor) {
        sql.sql("""
                UPDATE furgonetas SET capacidad_max_kg = :peso, capacidad_max_m3 = :volumen, max_paquetes = :paquetes,
                    actualizado_por = :actor, actualizado_en = now() WHERE id = :id
                """).param("id", id).param("peso", datos.capacidadPesoKg()).param("volumen", datos.capacidadVolumenM3())
                .param("paquetes", datos.maximoPaquetes()).param("actor", actor).update();
        return obtenerFurgoneta(id, false);
    }
    public void bloquearOperacion() {
        // El orden global evita carreras con escritores existentes que aún no usan los bloqueos de F-06.
        // Incluye las tablas físicas: un LOCK sobre una vista no protege todos sus escritores.
        sql.sql("SELECT public.bloquear_operacion_flota()").query(Object.class).single();
    }
    public void comprobarSinDespachos(UUID id, boolean repartidor) {
        String columna = repartidor ? "repartidor_id" : "furgoneta_id";
        boolean ocupado = sql.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM asignaciones_diarias jornada
                    WHERE jornada.""" + columna + """
                     = :id AND (
                        EXISTS (SELECT 1 FROM despachos_flota despacho
                            WHERE despacho.asignacion_diaria_id = jornada.id AND despacho.estado IN ('ASIGNADO', 'EN_CAMINO'))
                        OR EXISTS (SELECT 1 FROM reservas_capacidad reserva
                            WHERE reserva.asignacion_diaria_id = jornada.id AND reserva.estado = 'ACTIVA')
                    )
                )
                """).param("id", id).query(Boolean.class).single();
        if (ocupado) throw ConflictoFlota.conflicto("Existen despachos en curso o capacidad reservada pendiente de liberar.");
    }
    public void cerrarJornadas(UUID id, boolean repartidor, UUID actor) {
        String columna = repartidor ? "repartidor_id" : "furgoneta_id";
        var anteriores = sql.sql("SELECT id, to_jsonb(jornada)::text AS datos FROM asignaciones_diarias jornada"
                + " WHERE " + columna + " = :id AND estado = 'ACTIVA' ORDER BY id FOR UPDATE")
                .param("id", id).query((fila, indice) -> new JornadaAnterior(uuid(fila, "id"), fila.getString("datos"))).list();
        sql.sql("UPDATE asignaciones_diarias SET estado = 'CERRADA', cerrada_en = now(), tipo_cierre = :cierre"
                + " WHERE " + columna + " = :id AND estado = 'ACTIVA'")
                .param("id", id).param("cierre", repartidor ? "GESTOR_DESPACHO" : "MANTENIMIENTO").update();
        for (var jornada : anteriores) {
            auditar("asignaciones_diarias", jornada.id(), "CIERRE_JORNADA", jornada.datos(), actor);
        }
    }
    private record JornadaAnterior(UUID id, String datos) {}
    public void darDeBaja(UUID id, UUID actor) {
        sql.sql("UPDATE perfiles_flota SET estado_registro = 'INACTIVO', actualizado_por = :actor, actualizado_en = now() WHERE id = :id")
                .param("id", id).param("actor", actor).update();
    }
    public void vincular(UUID id, UUID usuario, UUID actor) {
        sql.sql("""
                UPDATE perfiles_flota SET id_usuario_seguridad = :usuario, estado_vinculacion = 'VINCULADO',
                    vinculado_en = now(), actualizado_por = :actor, actualizado_en = now() WHERE id = :id
                """).param("id", id).param("usuario", usuario).param("actor", actor).update();
    }
    public void cambiarEstado(UUID id, EstadoFurgoneta estado, UUID actor) {
        sql.sql("UPDATE furgonetas SET estado = :estado, actualizado_por = :actor, actualizado_en = now() WHERE id = :id")
                .param("id", id).param("estado", estado.persistido()).param("actor", actor).update();
    }
    public String fotografiar(String entidad, UUID id) {
        return sql.sql("SELECT to_jsonb(fila)::text FROM " + tablaAuditable(entidad) + " fila WHERE id = :id")
                .param("id", id).query(String.class).single();
    }
    public void auditar(String entidad, UUID id, String accion, String anterior, UUID actor) {
        String nuevo = fotografiar(entidad, id);
        sql.sql("""
                INSERT INTO auditoria_configuracion (id, entidad, entidad_id, accion, datos_anteriores, datos_nuevos, usuario_id, registrado_en)
                VALUES (:auditoria, :entidad, :id, :accion, CAST(:anterior AS jsonb), CAST(:nuevo AS jsonb), :actor, now())
                """).param("auditoria", UUID.randomUUID()).param("entidad", entidad).param("id", id.toString())
                .param("accion", accion).param("anterior", anterior).param("nuevo", nuevo).param("actor", actor).update();
    }
    private String tablaAuditable(String entidad) {
        return switch (entidad) {
            case "repartidores" -> "perfiles_flota";
            case "furgonetas" -> "furgonetas";
            case "asignaciones_diarias" -> "asignaciones_diarias";
            default -> throw new IllegalArgumentException("Entidad de auditoría no permitida");
        };
    }
    private static UUID uuid(ResultSet fila, String columna) throws SQLException { return fila.getObject(columna, UUID.class); }
    private static String nombre(Enum<?> valor) { return valor == null ? "" : valor.name(); }
}
