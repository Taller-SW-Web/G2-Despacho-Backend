package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

public final class GuardiaCancelacion {
    private GuardiaCancelacion() {}
    /** Se consulta bajo el bloqueo de despacho/fila; el cerco no expira con el arrendamiento técnico. */
    public static void exigirLibre(JdbcClient sql, UUID despacho) {
        if (sql.sql("SELECT EXISTS(SELECT 1 FROM cancelaciones_despacho WHERE despacho_id=:id AND estado='PENDIENTE')")
                .param("id", despacho).query(Boolean.class).single())
            throw ErrorDeNegocio.de(409, "DESP_ERROR_CANCELACION_EN_PROCESO", "El despacho tiene una cancelación en proceso");
    }
}
