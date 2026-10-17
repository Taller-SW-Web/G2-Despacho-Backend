package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.infrastructure;

import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.LectorEvidencias;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.ErrorEvidencia;

@Repository
@Profile("!integracion-local")
public class EvidenciasJdbc implements LectorEvidencias {
    private final JdbcClient sql;
    public EvidenciasJdbc(JdbcClient sql) { this.sql = sql; }

    @Override
    public boolean tienePerfilActivo(String idUsuario) {
        return sql.sql("""
                SELECT EXISTS(SELECT 1 FROM perfiles_flota WHERE id_usuario_seguridad::text = :usuario
                  AND estado_registro = 'ACTIVO' AND estado_vinculacion = 'VINCULADO')
                """).param("usuario", idUsuario).query(Boolean.class).single();
    }
    @Override
    public boolean esPropietario(String idUsuario, String idDespacho) {
        return sql.sql("""
                SELECT EXISTS(SELECT 1 FROM proyecciones_despacho p
                  JOIN asignaciones_diarias j ON j.id = p.asignacion_diaria_id
                  JOIN perfiles_flota r ON r.id = j.repartidor_id
                  WHERE p.despacho_id_externo::text = :despacho AND r.id_usuario_seguridad::text = :usuario
                    AND r.estado_registro = 'ACTIVO' AND r.estado_vinculacion = 'VINCULADO')
                """).param("despacho", idDespacho).param("usuario", idUsuario).query(Boolean.class).single();
    }
    @Override
    public Optional<Referencia> consultar(String idEvidencia) {
        return sql.sql("SELECT id, despacho_id_externo, ruta_objeto, estado FROM evidencias WHERE id::text = :id")
                .param("id", idEvidencia).query((fila, indice) -> new Referencia(fila.getString("id"),
                        fila.getString("despacho_id_externo"), fila.getString("ruta_objeto"), fila.getString("estado"))).optional();
    }
    @Override
    public int vigenciaSegundos() {
        String valor = sql.sql("SELECT valor FROM parametros_configuracion WHERE clave = 'VIGENCIA_URL_FIRMADA_SEGUNDOS'")
                .query(String.class).optional().orElse("");
        try { return Integer.parseInt(valor); } catch (NumberFormatException error) { throw ErrorEvidencia.noDisponible(); }
    }
}
