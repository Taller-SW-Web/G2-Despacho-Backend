package com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.infrastructure;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application.AlmacenCampo;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.domain.ModelosCampo.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;

@Repository
@Profile("!integracion-local")
public class AlmacenCampoJdbc implements AlmacenCampo {
    private final JdbcClient sql;
    public AlmacenCampoJdbc(JdbcClient sql) { this.sql = sql; }

    @Override
    @Transactional
    public Comando iniciar(UUID usuario, UUID clave, String operacion, String huella) {
        int insertadas = sql.sql("""
                INSERT INTO comandos_repartidor(id, usuario_id, clave, operacion, huella, estado)
                VALUES (:id, :usuario, :clave, :operacion, :huella, 'EN_PROCESO')
                ON CONFLICT (usuario_id, clave) DO NOTHING
                """).param("id", UUID.randomUUID()).param("usuario", usuario).param("clave", clave)
                .param("operacion", operacion).param("huella", huella).update();
        return sql.sql("SELECT operacion, huella, estado, respuesta::text FROM comandos_repartidor WHERE usuario_id = :usuario AND clave = :clave")
                .param("usuario", usuario).param("clave", clave).query((fila, indice) -> {
                    if (!operacion.equals(fila.getString(1)) || !huella.equals(fila.getString(2)))
                        throw new ErrorJornada(409, "DESP_ERROR_CLAVE_REUTILIZADA", "La clave corresponde a otra operación.");
                    return new Comando(insertadas == 1, fila.getString(3), fila.getString(4));
                }).single();
    }
    @Override
    @Transactional
    public void completar(UUID usuario, UUID clave, String respuesta) {
        sql.sql("""
                UPDATE comandos_repartidor SET estado = 'COMPLETADO', respuesta = CAST(:respuesta AS jsonb), completado_en = now()
                WHERE usuario_id = :usuario AND clave = :clave AND estado = 'EN_PROCESO'
                """).param("usuario", usuario).param("clave", clave).param("respuesta", respuesta).update();
    }
    @Override
    @Transactional
    public void descartar(UUID usuario, UUID clave) {
        sql.sql("DELETE FROM comandos_repartidor WHERE usuario_id = :usuario AND clave = :clave AND estado = 'EN_PROCESO'")
                .param("usuario", usuario).param("clave", clave).update();
    }
    @Override
    @Transactional(readOnly = true)
    public EvidenciaPendiente evidencia(UUID idEvidencia, UUID idDespacho, String tipo, String usuario) {
        // Solo la carga que el mismo repartidor autorizó para este despacho y acción puede asociarse.
        return sql.sql("""
                SELECT id, ruta_objeto, tipo_mime FROM evidencias
                WHERE id = :id AND despacho_id_externo = :despacho AND tipo = :tipo AND cargado_por = :usuario
                  AND estado IN ('AUTORIZADA', 'CARGADA')
                """).param("id", idEvidencia).param("despacho", idDespacho).param("tipo", tipo).param("usuario", usuario)
                .query((fila, indice) -> new EvidenciaPendiente(fila.getObject(1, UUID.class), fila.getString(2), fila.getString(3)))
                .optional().orElseThrow(() -> new ErrorJornada(400, "DESP_ERROR_EVIDENCIA_INVALIDA",
                        "La fotografía no corresponde a este despacho o ya fue utilizada."));
    }
    @Override
    @Transactional
    public void marcarCargada(UUID idEvidencia) {
        sql.sql("UPDATE evidencias SET estado = 'CARGADA', cargado_en = coalesce(cargado_en, now()) WHERE id = :id AND estado = 'AUTORIZADA'")
                .param("id", idEvidencia).update();
    }
    @Override
    @Transactional
    public void asociar(UUID idEvidencia) {
        sql.sql("UPDATE evidencias SET estado = 'ASOCIADA', asociada_en = now() WHERE id = :id AND estado IN ('AUTORIZADA', 'CARGADA')")
                .param("id", idEvidencia).update();
    }
}
