package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

public final class GuardiaReasignacion {
    private GuardiaReasignacion() {}
    /** Llamar después de adquirir el bloqueo de despacho o su fila: no es una lectura autorizadora aislada. */
    public static void exigirLibre(JdbcClient sql, UUID despacho) {
        GuardiaCancelacion.exigirLibre(sql, despacho);
        if (sql.sql("SELECT EXISTS(SELECT 1 FROM reasignaciones_despacho WHERE despacho_id=:id AND estado='PENDIENTE')")
                .param("id", despacho).query(Boolean.class).single())
            throw ErrorDeNegocio.de(409, "DESP_ERROR_REASIGNACION_EN_PROCESO", "El despacho tiene una reasignación en proceso");
    }
}
