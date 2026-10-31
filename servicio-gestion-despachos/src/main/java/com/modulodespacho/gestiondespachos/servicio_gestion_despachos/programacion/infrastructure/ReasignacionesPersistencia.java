package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.time.*;
import java.util.*;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.AlmacenReasignaciones;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.Despacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosReasignacion.*;

@Repository
@Profile("!integracion-local")
public class ReasignacionesPersistencia implements AlmacenReasignaciones {
    private final JdbcClient sql;
    private final JsonMapper json;
    private final EntityManager entidades;
    private final ProgramacionPersistencia programacion;
    public ReasignacionesPersistencia(JdbcClient sql, JsonMapper json, EntityManager entidades, ProgramacionPersistencia programacion) {
        this.sql=sql; this.json=json; this.entidades=entidades; this.programacion=programacion;
    }
    @Override @Transactional
    public Intencion preparar(UUID id, UUID clave, int version, Solicitud solicitud, String actor) {
        bloquear("reasignacion-clave:"+actor+":"+clave);
        String huella=json.writeValueAsString(List.of(id,version,solicitud));
        var repetida=sql.sql("SELECT solicitud,intencion::text FROM reasignaciones_despacho WHERE ejecutor_id=:actor AND clave=:clave")
                .param("actor",actor).param("clave",clave).query((r,i) -> {
                    if (!huella.equals(r.getString(1))) throw conflicto("DESP_ERROR_OPERACION_REUTILIZADA","La clave ya identifica otra solicitud");
                    return json.readValue(r.getString(2),Intencion.class);
                }).optional();
        if (repetida.isPresent()) return repetida.get();
        bloquear("asignacion:"+id);
        bloquearFila(id);
        GuardiaReasignacion.exigirLibre(sql,id);
        Despacho despacho=entidades.find(Despacho.class,id);
        if (!"ASIGNADO".equals(despacho.getEstado().name()) || despacho.getVersion()!=version)
            throw conflicto("DESP_ERROR_DESPACHO_DESACTUALIZADO","El despacho cambió. Consulta la ruta vigente");
        if (solicitud.idNuevoRepartidor().equals(despacho.getIdRepartidor()) || solicitud.idNuevaJornada().equals(despacho.getIdJornada()))
            throw ErrorDeNegocio.de(400,"DESP_ERROR_CAMPOS_INVALIDOS","El destino debe ser otro repartidor y jornada");
        if (despacho.getIdRepartidor()==null || despacho.getIdJornada()==null || despacho.getIdReserva()==null || despacho.getSecuenciaRuta()==null)
            throw conflicto("DESP_ERROR_DATOS_INCONSISTENTES","La asignación no tiene referencias operativas completas");
        var intencion=new Intencion(UUID.randomUUID(),id,clave,actor,version,despacho.getIdRepartidor(),despacho.getIdJornada(),despacho.getIdReserva(),solicitud);
        sql.sql("INSERT INTO reasignaciones_despacho(id,despacho_id,ejecutor_id,clave,solicitud,intencion,estado) VALUES (:id,:despacho,:actor,:clave,:solicitud,CAST(:intencion AS jsonb),'PENDIENTE')")
                .param("id",intencion.id()).param("despacho",id).param("actor",actor).param("clave",clave).param("solicitud",huella)
                .param("intencion",json.writeValueAsString(intencion)).update();
        return intencion;
    }
    @Override @Transactional(readOnly=true)
    public Optional<Respuesta> resultado(UUID id) {
        return sql.sql("SELECT respuesta::text FROM reasignaciones_despacho WHERE id=:id AND estado='CONFIRMADA'")
                .param("id",id).query((r,i) -> json.readValue(r.getString(1),Respuesta.class)).optional();
    }
    @Override @Transactional(readOnly=true)
    public Optional<Rechazo> rechazo(UUID id) {
        return sql.sql("SELECT rechazo::text FROM reasignaciones_despacho WHERE id=:id AND estado='RECHAZADA'")
                .param("id",id).query((r,i) -> json.readValue(r.getString(1),Rechazo.class)).optional();
    }
    @Override @Transactional
    public boolean reclamar(UUID id) {
        return sql.sql("UPDATE reasignaciones_despacho SET procesando_hasta=now()+interval '60 seconds' WHERE id=:id AND estado='PENDIENTE' AND (procesando_hasta IS NULL OR procesando_hasta<now())")
                .param("id",id).update()==1;
    }
    @Override @Transactional
    public void aplazar(UUID id) {
        sql.sql("UPDATE reasignaciones_despacho SET procesando_hasta=NULL,proximo_intento_en=now()+interval '30 seconds' WHERE id=:id AND estado='PENDIENTE'").param("id",id).update();
    }
    @Override @Transactional
    public void rechazar(UUID id, Rechazo rechazo) {
        sql.sql("UPDATE reasignaciones_despacho SET estado='RECHAZADA',rechazo=CAST(:rechazo AS jsonb),procesando_hasta=NULL WHERE id=:id AND estado='PENDIENTE'")
                .param("id",id).param("rechazo",json.writeValueAsString(rechazo)).update();
    }
    @Override @Transactional
    public Respuesta confirmar(Intencion intencion, Transferencia transferencia, Instant ahora) {
        bloquear("asignacion:"+intencion.idDespacho());
        String estado=sql.sql("SELECT estado FROM reasignaciones_despacho WHERE id=:id FOR UPDATE").param("id",intencion.id()).query(String.class).single();
        var repetida=resultado(intencion.id());
        if (repetida.isPresent()) return repetida.get();
        if (!"PENDIENTE".equals(estado)) throw conflicto("DESP_ERROR_REASIGNACION_RECHAZADA","La operación ya fue rechazada");
        // Orden global de jornadas, compartido con asignación y reordenamiento. Nunca retenerlo durante HTTP.
        java.util.stream.Stream.of(intencion.idJornadaOrigen(),intencion.solicitud().idNuevaJornada()).sorted().distinct()
                .forEach(jornada -> bloquear("secuencia-jornada:"+jornada));
        bloquearFila(intencion.idDespacho());
        Despacho d=entidades.find(Despacho.class,intencion.idDespacho());
        if (d.getVersion()!=intencion.versionOrigen() || !"ASIGNADO".equals(d.getEstado().name())
                || !Objects.equals(d.getIdReserva(),intencion.idReservaOrigen()))
            throw conflicto("DESP_ERROR_DESPACHO_DESACTUALIZADO","El despacho no coincide con la intención durable; requiere recuperación");
        int posicionAnterior=d.getSecuenciaRuta();
        int posicion=sql.sql("SELECT coalesce(max(secuencia_ruta),0)+1 FROM despachos WHERE asignacion_diaria_id_externa=:jornada")
                .param("jornada",intencion.solicitud().idNuevaJornada()).query(Integer.class).single();
        d.setIdRepartidor(intencion.solicitud().idNuevoRepartidor()); d.setIdJornada(intencion.solicitud().idNuevaJornada());
        d.setIdReserva(transferencia.idNuevaReserva()); d.setSecuenciaRuta(posicion); d.setActualizadoEn(ahora);
        entidades.flush();
        Map<String,Object> datos=new LinkedHashMap<>();
        datos.put("idRepartidor",intencion.idRepartidorOrigen()); datos.put("idJornada",intencion.idJornadaOrigen());
        datos.put("idNuevoRepartidor",d.getIdRepartidor()); datos.put("idNuevaJornada",d.getIdJornada());
        datos.put("idReservaAnterior",intencion.idReservaOrigen()); datos.put("idNuevaReserva",d.getIdReserva());
        datos.put("posicionAnterior",posicionAnterior); datos.put("secuenciaRuta",posicion); datos.put("motivo",intencion.solicitud().motivo());
        datos.put("capacidadRemanente",transferencia.capacidadRemanente()); datos.put("transferidaEn",transferencia.transferidaEn());
        datos.put("cargaReservada",Map.of("pesoKg",d.getPesoKg(),"volumenM3",d.getVolumenM3(),"paquetes",d.getCantidadPaquetes()));
        sql.sql("""
                INSERT INTO historial_estados_despacho(despacho_id,tipo_operacion,estado_anterior,estado_nuevo,funcionalidad_origen,
                    ejecutor_tipo,ejecutor_id,origen,observaciones,datos,registrado_en)
                VALUES (:id,'REASIGNACION','ASIGNADO','ASIGNADO','F-02','USUARIO',:actor,'MANUAL',:motivo,CAST(:datos AS jsonb),:ahora)
                """).param("id",d.getId()).param("actor",intencion.ejecutor()).param("motivo",intencion.solicitud().motivo())
                .param("datos",json.writeValueAsString(datos)).param("ahora",ahora.atOffset(ZoneOffset.UTC)).update();
        programacion.publicarProyeccion(d,ahora);
        var respuesta=new Respuesta(d.getId().toString(),"ASIGNADO",d.getVersion(),intencion.idRepartidorOrigen(),intencion.idJornadaOrigen(),
                d.getIdRepartidor(),d.getIdJornada(),d.getIdReserva(),posicion,intencion.solicitud().motivo(),ahora);
        sql.sql("UPDATE reasignaciones_despacho SET estado='CONFIRMADA',respuesta=CAST(:respuesta AS jsonb),procesando_hasta=NULL WHERE id=:id")
                .param("id",intencion.id()).param("respuesta",json.writeValueAsString(respuesta)).update();
        return respuesta;
    }
    @Override @Transactional(readOnly=true)
    public List<Intencion> recuperables() {
        return sql.sql("SELECT intencion::text FROM reasignaciones_despacho WHERE estado='PENDIENTE' AND proximo_intento_en<=now() AND (procesando_hasta IS NULL OR procesando_hasta<now()) ORDER BY creado_en,id LIMIT 20")
                .query((r,i) -> json.readValue(r.getString(1),Intencion.class)).list();
    }
    private void bloquearFila(UUID id) {
        if (sql.sql("SELECT id FROM despachos WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().isEmpty())
            throw ErrorDeNegocio.de(404,"DESP_ERROR_DESPACHO_NO_ENCONTRADO","El despacho no existe");
    }
    private void bloquear(String clave) { sql.sql("SELECT pg_advisory_xact_lock(hashtextextended(:clave,0))").param("clave",clave).query((r,i) -> true).single(); }
    private static ErrorDeNegocio conflicto(String codigo,String mensaje) { return ErrorDeNegocio.de(409,codigo,mensaje); }
}
