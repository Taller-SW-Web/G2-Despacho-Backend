package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.IRepositorioJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;

@Repository
@Profile("!integracion-local")
public class RepositorioJornadasJdbc implements IRepositorioJornadas {
    private final JdbcClient sql;
    private final JsonMapper json;
    public RepositorioJornadasJdbc(JdbcClient sql, JsonMapper json) { this.sql = sql; this.json = json; }
    private static final String CAMPOS = """
        SELECT r.id AS repartidor_id, r.nombres || ' ' || r.apellidos AS nombre,
          r.estado_registro, r.estado_vinculacion, j.id AS jornada_id, j.furgoneta_id, f.placa,
          f.estado AS estado_vehiculo, f.capacidad_max_kg AS maestro_peso, f.capacidad_max_m3 AS maestro_volumen,
          f.max_paquetes AS maestro_paquetes, j.zona_id_externa, j.fecha_jornada, j.estado AS estado_jornada,
          j.cerrada_en, j.capacidad_max_kg, j.capacidad_max_m3, j.max_paquetes,
          COALESCE(carga.peso,0) AS peso, COALESCE(carga.volumen,0) AS volumen, COALESCE(carga.paquetes,0) AS paquetes,
          COALESCE(proyeccion.en_camino,false) AS en_camino,
          (COALESCE(proyeccion.pendientes,false) OR COALESCE(carga.sin_confirmar,false)) AS pendientes
        """;
    private static final String CARGA = """
        LEFT JOIN furgonetas f ON f.id=j.furgoneta_id
        LEFT JOIN LATERAL (
          SELECT sum(r.peso_kg) AS peso, sum(r.volumen_m3) AS volumen, sum(r.cantidad_paquetes) AS paquetes,
            bool_or(NOT EXISTS(SELECT 1 FROM proyecciones_despacho p WHERE p.reserva_capacidad_id=r.id
              AND p.estado IN ('FALLIDO','ENTREGADO','CANCELADO','DEVUELTO_A_ORIGEN','PENDIENTE_ASIGNACION'))) AS sin_confirmar
          FROM reservas_capacidad r WHERE r.asignacion_diaria_id=j.id AND r.estado='ACTIVA'
        ) carga ON true
        LEFT JOIN LATERAL (
          SELECT bool_or(estado='EN_CAMINO') AS en_camino,
            bool_or(estado IN ('ASIGNADO','EN_CAMINO')) AS pendientes
          FROM proyecciones_despacho WHERE asignacion_diaria_id=j.id
        ) proyeccion ON true
        """;

    @Override public void bloquearOperacion() { sql.sql("SELECT public.bloquear_operacion_flota()").query(Boolean.class).single(); }
    @Override public List<FilaBase> consultarMonitoreo(LocalDate fecha) {
        return sql.sql(CAMPOS + """
            FROM perfiles_flota r
            LEFT JOIN LATERAL (
              SELECT * FROM asignaciones_diarias WHERE repartidor_id=r.id AND fecha_jornada=:fecha
              ORDER BY (estado='ACTIVA') DESC, abierta_en DESC, id DESC LIMIT 1
            ) j ON true
            """ + CARGA + " WHERE r.estado_registro='ACTIVO' ORDER BY r.apellidos,r.nombres,r.id")
                .param("fecha",fecha).query(this::fila).list();
    }
    @Override public JornadaBase obtenerJornada(UUID id) {
        return sql.sql(CAMPOS + " FROM asignaciones_diarias j JOIN perfiles_flota r ON r.id=j.repartidor_id " + CARGA + " WHERE j.id=:id")
                .param("id",id).query(this::fila).optional().map(FilaBase::jornada)
                .orElseThrow(() -> new ErrorJornada(404,"DESP_ERROR_JORNADA_NO_ENCONTRADA","La jornada no existe."));
    }
    @Override public Optional<JornadaBase> buscarJornadaDelDia(UUID repartidor, LocalDate fecha) {
        return sql.sql(CAMPOS + """
            FROM asignaciones_diarias j JOIN perfiles_flota r ON r.id=j.repartidor_id
            """ + CARGA + """
            WHERE j.repartidor_id=:repartidor AND j.fecha_jornada=:fecha
            ORDER BY (j.estado='ACTIVA') DESC,j.abierta_en DESC,j.id DESC LIMIT 1
            """).param("repartidor",repartidor).param("fecha",fecha).query(this::fila).optional().map(FilaBase::jornada);
    }
    private FilaBase fila(ResultSet fila, int indice) throws SQLException {
        Perfil perfil = new Perfil(fila.getObject("repartidor_id",UUID.class),fila.getString("nombre"),fila.getString("estado_registro"),fila.getString("estado_vinculacion"));
        UUID id = fila.getObject("jornada_id",UUID.class);
        if (id == null) return new FilaBase(perfil,null,null);
        Capacidad limites = new Capacidad(fila.getBigDecimal("capacidad_max_kg"),fila.getBigDecimal("capacidad_max_m3"),fila.getInt("max_paquetes"));
        long paquetes = fila.getLong("paquetes");
        if (paquetes > Integer.MAX_VALUE) throw ErrorJornada.noDisponible();
        Capacidad ocupacion = new Capacidad(fila.getBigDecimal("peso"),fila.getBigDecimal("volumen"),(int)paquetes);
        var cierre = fila.getTimestamp("cerrada_en");
        UUID vehiculo = fila.getObject("furgoneta_id",UUID.class);
        return new FilaBase(perfil,new Vehiculo(vehiculo,fila.getString("placa"),fila.getString("estado_vehiculo"),
                new Capacidad(fila.getBigDecimal("maestro_peso"),fila.getBigDecimal("maestro_volumen"),fila.getInt("maestro_paquetes"))),
                new JornadaBase(id,perfil.id(),vehiculo,fila.getString("placa"),fila.getObject("zona_id_externa",UUID.class),
                fila.getObject("fecha_jornada",LocalDate.class),fila.getString("estado_jornada"),cierre == null ? null : cierre.toInstant(),
                limites,ocupacion,fila.getBoolean("en_camino"),fila.getBoolean("pendientes")));
    }
    @Override public Perfil obtenerPerfil(UUID id) {
        return sql.sql("SELECT id,nombres || ' ' || apellidos AS nombre,estado_registro,estado_vinculacion FROM perfiles_flota WHERE id=:id FOR SHARE")
                .param("id",id).query((fila,indice) -> perfil(fila)).optional()
                .orElseThrow(() -> ErrorJornada.noApto("El repartidor no existe."));
    }
    private Perfil perfil(ResultSet fila) throws SQLException { return new Perfil(fila.getObject("id",UUID.class),fila.getString("nombre"),fila.getString("estado_registro"),fila.getString("estado_vinculacion")); }
    @Override public Optional<Perfil> buscarPerfilDeUsuario(UUID usuario) {
        return sql.sql("SELECT id,nombres || ' ' || apellidos AS nombre,estado_registro,estado_vinculacion FROM perfiles_flota WHERE id_usuario_seguridad=:usuario")
                .param("usuario",usuario).query((fila,indice) -> perfil(fila)).optional();
    }
    @Override public Vehiculo obtenerVehiculo(UUID id) {
        return sql.sql("SELECT id,placa,estado,capacidad_max_kg,capacidad_max_m3,max_paquetes FROM furgonetas WHERE id=:id FOR SHARE")
                .param("id",id).query((fila,indice) -> new Vehiculo(id,fila.getString("placa"),fila.getString("estado"),
                new Capacidad(fila.getBigDecimal("capacidad_max_kg"),fila.getBigDecimal("capacidad_max_m3"),fila.getInt("max_paquetes"))))
                .optional().orElseThrow(() -> ErrorJornada.conflicto("La furgoneta no existe o dejó de estar disponible."));
    }
    @Override public boolean tieneRecursoOcupado(UUID repartidor, UUID furgoneta) {
        return sql.sql("""
            SELECT EXISTS(SELECT 1 FROM asignaciones_diarias j
              WHERE (j.repartidor_id=:repartidor OR j.furgoneta_id=:furgoneta) AND
                (j.estado='ACTIVA' OR EXISTS(SELECT 1 FROM reservas_capacidad r WHERE r.asignacion_diaria_id=j.id AND r.estado='ACTIVA')
                 OR EXISTS(SELECT 1 FROM proyecciones_despacho p WHERE p.asignacion_diaria_id=j.id AND p.estado IN ('ASIGNADO','EN_CAMINO'))))
            """).param("repartidor",repartidor).param("furgoneta",furgoneta).query(Boolean.class).single();
    }
    @Override public UUID crear(Apertura datos, Capacidad capacidad, UUID actor, Instant ahora) {
        UUID id = UUID.randomUUID();
        sql.sql("""
            INSERT INTO asignaciones_diarias (id,repartidor_id,furgoneta_id,zona_id_externa,fecha_jornada,
              capacidad_max_kg,capacidad_max_m3,max_paquetes,estado,abierta_en,abierta_por)
            VALUES (:id,:repartidor,:furgoneta,:zona,:fecha,:peso,:volumen,:paquetes,'ACTIVA',:ahora,:actor)
            """).param("id",id).param("repartidor",datos.idRepartidor()).param("furgoneta",datos.idFurgoneta()).param("zona",datos.idZona())
                .param("fecha",datos.fecha()).param("peso",capacidad.pesoKg()).param("volumen",capacidad.volumenM3())
                .param("paquetes",capacidad.paquetes()).param("ahora",java.sql.Timestamp.from(ahora)).param("actor",actor).update();
        return id;
    }
    @Override public void cerrar(UUID id,String tipo,Instant ahora) {
        sql.sql("UPDATE asignaciones_diarias SET estado='CERRADA',cerrada_en=:ahora,tipo_cierre=:tipo WHERE id=:id AND estado='ACTIVA'")
                .param("id",id).param("tipo",tipo).param("ahora",java.sql.Timestamp.from(ahora)).update();
    }
    @Override public String fotografiar(UUID id) { return sql.sql("SELECT to_jsonb(j)::text FROM asignaciones_diarias j WHERE id=:id").param("id",id).query(String.class).single(); }
    @Override public void auditar(UUID id,String accion,UUID actor,String anterior) {
        sql.sql("""
            INSERT INTO auditoria_configuracion (id,entidad,entidad_id,accion,datos_anteriores,datos_nuevos,usuario_id)
            VALUES (:auditoria,'asignaciones_diarias',:id,:accion,CAST(:anterior AS jsonb),CAST(:nuevo AS jsonb),:actor)
            """).param("auditoria",UUID.randomUUID()).param("id",id.toString()).param("accion",accion)
                .param("anterior",anterior).param("nuevo",fotografiar(id)).param("actor",actor).update();
    }
    @Override public List<DespachoResumen> listarDespachos(UUID id) {
        return sql.sql("SELECT despacho_id_externo,estado,secuencia_ruta FROM proyecciones_despacho WHERE asignacion_diaria_id=:id ORDER BY secuencia_ruta NULLS LAST,despacho_id_externo")
                .param("id",id).query((fila,indice) -> new DespachoResumen(fila.getObject("despacho_id_externo",UUID.class),fila.getString("estado"),fila.getObject("secuencia_ruta",Integer.class))).list();
    }
    private static final String PROYECCION = """
        SELECT despacho_id_externo,id_pedido,codigo_rastreo_interno,estado,asignacion_diaria_id,
          reserva_capacidad_id,secuencia_ruta,destinatario_nombre,destinatario_telefono,direccion,
          referencia,distrito,fecha_programada,numero_intentos,maximo_intentos,version,actualizado_en,
          payload_proyeccion::text AS payload
        FROM proyecciones_despacho
        """;
    @Override public List<Proyeccion> listarProyecciones(UUID jornada) {
        return sql.sql(PROYECCION + """
                WHERE asignacion_diaria_id=:jornada AND estado <> 'CANCELADO'
                ORDER BY secuencia_ruta NULLS LAST,despacho_id_externo
                """).param("jornada",jornada).query(this::proyeccion).list();
        }
        @Override public Optional<Proyeccion> buscarProyeccion(UUID jornada, UUID despacho) {
            return sql.sql(PROYECCION + """
                WHERE asignacion_diaria_id=:jornada AND despacho_id_externo=:despacho AND estado <> 'CANCELADO'
                """).param("jornada",jornada).param("despacho",despacho).query(this::proyeccion).optional();
    }
    private Proyeccion proyeccion(ResultSet fila,int indice) throws SQLException {
        String payload = fila.getString("payload");
        if (payload != null) return json.readValue(payload,Proyeccion.class);
        var actualizado = fila.getTimestamp("actualizado_en");
        return new Proyeccion(fila.getObject("despacho_id_externo",UUID.class),fila.getString("id_pedido"),
                fila.getString("codigo_rastreo_interno"),fila.getString("estado"),
                fila.getObject("asignacion_diaria_id",UUID.class),fila.getObject("reserva_capacidad_id",UUID.class),
                fila.getObject("secuencia_ruta",Integer.class),
                new Destinatario(fila.getString("destinatario_nombre"),fila.getString("destinatario_telefono")),
                new Destino(fila.getString("direccion"),fila.getString("referencia"),null,null,
                        fila.getString("distrito"),null,null),fila.getObject("fecha_programada",LocalDate.class),
                fila.getInt("numero_intentos"),fila.getLong("version"),actualizado == null ? null : actualizado.toInstant(),
                (Integer) fila.getObject("maximo_intentos"),null,null);
    }
    private static final String RESERVA = "SELECT r.*,j.repartidor_id FROM reservas_capacidad r JOIN asignaciones_diarias j ON j.id=r.asignacion_diaria_id ";
    private Reserva mapearReserva(ResultSet fila,int indice) throws SQLException {
        var liberacion = fila.getTimestamp("liberado_en");
        return new Reserva(fila.getObject("id",UUID.class),fila.getObject("clave_asignacion",UUID.class),fila.getObject("despacho_id_externo",UUID.class),
                fila.getObject("asignacion_diaria_id",UUID.class),fila.getObject("repartidor_id",UUID.class),
                new Capacidad(fila.getBigDecimal("peso_kg"),fila.getBigDecimal("volumen_m3"),fila.getInt("cantidad_paquetes")),
                fila.getString("estado"),fila.getString("motivo_liberacion"),liberacion == null ? null : liberacion.toInstant(),fila.getString("respuesta_confirmacion"));
    }
    @Override public Optional<Reserva> buscarReservaPorClave(UUID clave) { return sql.sql(RESERVA+"WHERE clave_asignacion=:clave").param("clave",clave).query(this::mapearReserva).optional(); }
    @Override public Reserva obtenerReserva(UUID id) { return sql.sql(RESERVA+"WHERE r.id=:id").param("id",id).query(this::mapearReserva).optional()
            .orElseThrow(() -> new ErrorJornada(404,"DESP_ERROR_RESERVA_NO_ENCONTRADA","La reserva no existe.")); }
    @Override public boolean tieneReservaActiva(UUID id) { return sql.sql("SELECT EXISTS(SELECT 1 FROM reservas_capacidad WHERE despacho_id_externo=:id AND estado='ACTIVA')").param("id",id).query(Boolean.class).single(); }
    @Override public void guardarReserva(UUID id, UUID clave, SolicitudReserva datos, String respuesta, String actor, Instant ahora) {
        sql.sql("""
            INSERT INTO reservas_capacidad (id,clave_asignacion,despacho_id_externo,asignacion_diaria_id,peso_kg,volumen_m3,
              cantidad_paquetes,estado,respuesta_confirmacion,registrada_por,creado_en)
            VALUES (:id,:clave,:despacho,:jornada,:peso,:volumen,:paquetes,'ACTIVA',CAST(:respuesta AS jsonb),:actor,:ahora)
            """).param("id",id).param("clave",clave).param("despacho",datos.idDespacho()).param("jornada",datos.idJornada())
                .param("peso",datos.requerido().pesoKg()).param("volumen",datos.requerido().volumenM3()).param("paquetes",datos.requerido().paquetes())
                .param("respuesta",respuesta).param("actor",actor).param("ahora",java.sql.Timestamp.from(ahora)).update();
    }
    @Override public void liberar(UUID id,String motivo,String actor,Instant ahora) {
        sql.sql("UPDATE reservas_capacidad SET estado='LIBERADA',motivo_liberacion=:motivo,liberado_en=:ahora,liberada_por=:actor,version=version+1 WHERE id=:id AND estado='ACTIVA'")
                .param("id",id).param("motivo",motivo).param("actor",actor).param("ahora",java.sql.Timestamp.from(ahora)).update();
    }
    @Override public Optional<Long> buscarVersionProyeccion(UUID id) { return sql.sql("SELECT version FROM proyecciones_despacho WHERE despacho_id_externo=:id").param("id",id).query(Long.class).optional(); }
    @Override public Optional<String> buscarTransferencia(UUID clave, String solicitud) {
        return sql.sql("SELECT solicitud,respuesta::text FROM transferencias_capacidad WHERE clave=:clave").param("clave",clave)
                .query((r,i) -> {
                    if (!solicitud.equals(r.getString(1))) throw new ErrorJornada(409,"DESP_ERROR_CLAVE_REUTILIZADA","La clave corresponde a otra transferencia.");
                    return r.getString(2);
                }).optional();
    }
    @Override public void guardarTransferencia(UUID clave, String solicitud, String respuesta, String actor) {
        sql.sql("INSERT INTO transferencias_capacidad(clave,solicitud,respuesta,ejecutor_id) VALUES (:clave,:solicitud,CAST(:respuesta AS jsonb),:actor)")
                .param("clave",clave).param("solicitud",solicitud).param("respuesta",respuesta).param("actor",actor).update();
    }
    @Override public void guardarProyeccion(Proyeccion d,String actor) {
        // El bloqueo común hace atómica esta decisión también cuando el esquema heredado expone una vista escribible.
        boolean existe = buscarVersionProyeccion(d.idDespacho()).isPresent();
        String sentencia = existe ? """
            UPDATE proyecciones_despacho SET id_pedido=:pedido,codigo_rastreo_interno=:rastreo,
              asignacion_diaria_id=:jornada,reserva_capacidad_id=:reserva,secuencia_ruta=:secuencia,estado=:estado,
              destinatario_nombre=:nombre,destinatario_telefono=:telefono,direccion=:direccion,referencia=:referencia,
              distrito=:distrito,fecha_programada=:fecha,numero_intentos=:intentos,version=:version,
              maximo_intentos=:maximo,actualizado_en=:ahora,payload_proyeccion=CAST(:payload AS jsonb),actualizado_por=:actor WHERE despacho_id_externo=:id
            """ : """
            INSERT INTO proyecciones_despacho (despacho_id_externo,id_pedido,codigo_rastreo_interno,asignacion_diaria_id,
              reserva_capacidad_id,secuencia_ruta,estado,destinatario_nombre,destinatario_telefono,direccion,referencia,
              distrito,fecha_programada,numero_intentos,maximo_intentos,version,actualizado_en,payload_proyeccion,actualizado_por)
            VALUES (:id,:pedido,:rastreo,:jornada,:reserva,:secuencia,:estado,:nombre,:telefono,:direccion,:referencia,
              :distrito,:fecha,:intentos,:maximo,:version,:ahora,CAST(:payload AS jsonb),:actor)
            """;
        sql.sql(sentencia).param("id",d.idDespacho()).param("pedido",d.idPedido()).param("rastreo",d.codigoRastreoInterno())
                .param("jornada",d.idJornada()).param("reserva",d.idReserva()).param("secuencia",d.secuenciaRuta()).param("estado",d.estado())
                .param("nombre",d.destinatario().nombre()).param("telefono",d.destinatario().telefono()).param("direccion",d.destino().direccion())
                .param("referencia",d.destino().referencia()).param("distrito",d.destino().distrito()).param("fecha",d.fechaProgramada())
                .param("intentos",d.numeroIntentos()).param("maximo",d.maximoIntentos()).param("version",d.version()).param("ahora",java.sql.Timestamp.from(d.actualizadoEn()))
                .param("payload",json.writeValueAsString(d)).param("actor",actor).update();
    }
}
