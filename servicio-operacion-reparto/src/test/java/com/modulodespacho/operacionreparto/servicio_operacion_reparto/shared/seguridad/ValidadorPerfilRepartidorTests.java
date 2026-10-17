package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Dobles de JDBC: comprueban denegación y fallos, no sustituyen las pruebas PostgreSQL. */
class ValidadorPerfilRepartidorTests {
    private final JdbcClient sql = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final ValidadorPerfilRepartidor validador = new ValidadorPerfilRepartidor(sql);
    private final UUID usuario = UUID.randomUUID();

    @Test
    void rechazaAnonimoServicioEIdentidadInvalidaSinConsultarDatos() {
        assertThat(validador.permite(null)).isFalse();
        assertThat(validador.permite(token("servicio", usuario.toString()))).isFalse();
        assertThat(validador.permite(token("acceso", "identidad-invalida"))).isFalse();
        var noAutenticado = token("acceso", usuario.toString());
        noAutenticado.setAuthenticated(false);
        assertThat(validador.permite(noAutenticado)).isFalse();
        verifyNoInteractions(sql);
    }

    @Test
    void usaElSujetoParaConsultarElPerfilSinExigirRolGlobal() {
        var consulta = sql.sql(anyString()).param(eq("usuario"), eq(usuario)).query(Boolean.class);
        when(consulta.single()).thenReturn(true);
        assertThat(validador.permite(token("acceso", usuario.toString()))).isTrue();
        when(consulta.single()).thenReturn(false);
        assertThat(validador.permite(token("acceso", usuario.toString()))).isFalse();
    }

    @Test
    void unaCaidaDeDatosNoConcedeAccesoNiSeConfundeConPerfilInexistente() {
        when(sql.sql(anyString())).thenThrow(new DataAccessResourceFailureException("detalle interno"));
        assertThatThrownBy(() -> validador.permite(token("acceso", usuario.toString())))
                .isInstanceOf(PerfilRepartidorNoDisponible.class)
                .hasMessageNotContaining("detalle interno");
    }

    private JwtAuthenticationToken token(String tipo, String sujeto) {
        Jwt jwt = Jwt.withTokenValue("prueba").header("alg", "RS256").subject(sujeto).claim("tipo", tipo).build();
        return new JwtAuthenticationToken(jwt, List.of());
    }
}
