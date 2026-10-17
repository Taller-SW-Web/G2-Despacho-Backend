package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@Profile("!integracion-local")
public class ValidadorPerfilRepartidor {
    private final JdbcClient sql;
    public ValidadorPerfilRepartidor(JdbcClient sql) { this.sql = sql; }

    public boolean permite(Authentication identidad) {
        if (!(identidad instanceof JwtAuthenticationToken jwt) || !identidad.isAuthenticated()
                || !"acceso".equals(jwt.getToken().getClaimAsString("tipo"))) return false;
        UUID usuario;
        try { usuario = UUID.fromString(jwt.getToken().getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) { return false; }
        try {
            return sql.sql("""
                    SELECT EXISTS (SELECT 1 FROM perfiles_flota
                        WHERE id_usuario_seguridad = :usuario AND estado_registro = 'ACTIVO'
                            AND estado_vinculacion = 'VINCULADO')
                    """).param("usuario", usuario).query(Boolean.class).single();
        } catch (DataAccessException error) {
            throw new PerfilRepartidorNoDisponible(error);
        }
    }
}
