package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.infrastructure;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.TipoEvidencia;

@Repository
@Profile("!integracion-local")
public class RepositorioEvidenciaJdbc {
    private final JdbcClient sql;

    public RepositorioEvidenciaJdbc(JdbcClient sql) {
        this.sql = sql;
    }

    public boolean perteneceADespachadorEnRuta(UUID idDespacho, UUID usuarioId) {
        return sql.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM proyecciones_despacho d
                    JOIN asignaciones_diarias j ON j.id = d.asignacion_diaria_id
                    JOIN repartidores r ON r.id = j.repartidor_id
                    WHERE d.despacho_id_externo = :despacho
                      AND r.id_usuario_seguridad = :usuario
                      AND r.estado_registro = 'ACTIVO'
                      AND r.estado_vinculacion = 'VINCULADO'
                      AND j.estado = 'ACTIVA'
                      AND d.estado = 'EN_CAMINO'
                )
                """)
                .param("despacho", idDespacho)
                .param("usuario", usuarioId)
                .query(Boolean.class)
                .single();
    }

    public boolean perteneceARecojoEnRuta(UUID idRecojo, UUID usuarioId) {
        return sql.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM proyecciones_recojo p
                    JOIN asignaciones_diarias j ON j.id = p.asignacion_diaria_id
                    JOIN repartidores r ON r.id = j.repartidor_id
                    WHERE p.recojo_id_externo = :recojo
                      AND r.id_usuario_seguridad = :usuario
                      AND r.estado_registro = 'ACTIVO'
                      AND r.estado_vinculacion = 'VINCULADO'
                      AND j.estado = 'ACTIVA'
                      AND p.estado = 'EN_CAMINO'
                )
                """)
                .param("recojo", idRecojo)
                .param("usuario", usuarioId)
                .query(Boolean.class)
                .single();
    }

    public boolean registrarAutorizadaRecojo(UUID idEvidencia, UUID idRecojo, String rutaObjeto,
            TipoEvidencia tipo, String contentType, int tamanioBytes, String usuarioId, UUID idUsuario,
            Instant venceEn) {
        return sql.sql("""
                WITH recojo_autorizable AS MATERIALIZED (
                    SELECT p.despacho_id_externo
                    FROM proyecciones_recojo p
                    JOIN asignaciones_diarias j ON j.id = p.asignacion_diaria_id
                    JOIN repartidores r ON r.id = j.repartidor_id
                    WHERE p.recojo_id_externo = :recojo
                      AND r.id_usuario_seguridad = :idUsuario
                      AND r.estado_registro = 'ACTIVO'
                      AND r.estado_vinculacion = 'VINCULADO'
                      AND j.estado = 'ACTIVA'
                      AND p.estado = 'EN_CAMINO'
                    FOR SHARE OF p, j, r
                )
                INSERT INTO evidencias (
                    id, despacho_id_externo, recojo_id_externo, ruta_objeto, tipo, tipo_mime, tamano_bytes,
                    estado, cargado_por, autorizada_en, expira_en
                )
                SELECT
                    :id, despacho_id_externo, :recojo, :ruta, :tipo, :mime, :tamano,
                    'AUTORIZADA', :usuario, now(), :venceEn
                FROM recojo_autorizable
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", idEvidencia)
                .param("recojo", idRecojo)
                .param("ruta", rutaObjeto)
                .param("tipo", tipo.name())
                .param("mime", contentType)
                .param("tamano", tamanioBytes)
                .param("usuario", usuarioId)
                .param("idUsuario", idUsuario)
                .param("venceEn", java.sql.Timestamp.from(venceEn))
                .update() == 1;
    }

    public boolean registrarAutorizada(UUID idEvidencia, UUID idDespacho, String rutaObjeto,
            TipoEvidencia tipo, String contentType, int tamanioBytes, String usuarioId, UUID idUsuario,
            Instant venceEn) {
        int filas = sql.sql("""
                WITH despacho_autorizable AS MATERIALIZED (
                    SELECT d.despacho_id_externo
                    FROM proyecciones_despacho d
                    JOIN asignaciones_diarias j ON j.id = d.asignacion_diaria_id
                    JOIN repartidores r ON r.id = j.repartidor_id
                    WHERE d.despacho_id_externo = :despacho
                      AND r.id_usuario_seguridad = :idUsuario
                      AND r.estado_registro = 'ACTIVO'
                      AND r.estado_vinculacion = 'VINCULADO'
                      AND j.estado = 'ACTIVA'
                      AND d.estado = 'EN_CAMINO'
                    FOR SHARE OF d, j, r
                )
                INSERT INTO evidencias (
                    id, despacho_id_externo, ruta_objeto, tipo, tipo_mime, tamano_bytes,
                    estado, cargado_por, autorizada_en, expira_en
                )
                SELECT
                    :id, :despacho, :ruta, :tipo, :mime, :tamano,
                    'AUTORIZADA', :usuario, now(), :venceEn
                WHERE EXISTS (
                    SELECT 1 FROM despacho_autorizable
                )
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", idEvidencia)
                .param("despacho", idDespacho)
                .param("ruta", rutaObjeto)
                .param("tipo", tipo.name())
                .param("mime", contentType)
                .param("tamano", tamanioBytes)
                .param("usuario", usuarioId)
                .param("idUsuario", idUsuario)
                .param("venceEn", java.sql.Timestamp.from(venceEn))
                .update();
        return filas == 1;
    }
}
