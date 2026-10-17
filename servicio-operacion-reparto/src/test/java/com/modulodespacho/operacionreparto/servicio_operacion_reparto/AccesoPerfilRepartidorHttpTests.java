package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** La ruta de prueba acredita el filtro de acceso, no implementa operaciones de entrega de F-03. */
@SpringBootTest(properties = "SEGURIDAD_URL=http://127.0.0.1:1")
@Import({TestcontainersConfiguration.class, AccesoPerfilRepartidorHttpTests.ControladorCampoDePrueba.class})
@AutoConfigureMockMvc
class AccesoPerfilRepartidorHttpTests {
    static final String RUTA = "/api/v1/repartidor/prueba-perfil";
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @MockitoBean JwtDecoder decodificador;

    @BeforeEach
    void preparar() {
        sql.sql("TRUNCATE auditoria_configuracion, proyecciones_despacho, reservas_capacidad, asignaciones_diarias, repartidores, furgonetas").update();
        when(decodificador.decode("gestor")).thenReturn(token("acceso", GestionFlotaHttpTests.ACTOR, List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("campo")).thenReturn(token("acceso", GestionFlotaHttpTests.ACTOR, List.of()));
        when(decodificador.decode("servicio")).thenReturn(token("servicio", GestionFlotaHttpTests.ACTOR, List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("desconocido")).thenReturn(token("acceso", UUID.randomUUID(), List.of()));
    }
    @Test
    void exigeVinculoActivoYLaBajaImpideElAccesoSinRolGlobalDeRepartidor() throws Exception {
        acceso("campo", 403);
        UUID id = crearPerfil();
        acceso("campo", 403);
        vincular(id);
        acceso("campo", 200);
        http.perform(patch("/api/v1/repartidores/" + id + "/estado").header("Authorization", "Bearer gestor")
                .contentType("application/json").content("{\"estado\":\"INACTIVO\"}")).andExpect(status().isNoContent());
        acceso("campo", 403);
    }
    @Test
    void rechazaTokensDeServicioUsuariosSinPerfilYAccesoAnonimo() throws Exception {
        UUID id = crearPerfil();
        vincular(id);
        acceso("servicio", 403);
        acceso("desconocido", 403);
        http.perform(get(RUTA)).andExpect(status().isUnauthorized());
        acceso("campo", 200);
    }
    @Test
    void informaIndisponibilidadSinPermitirAccesoNiExponerSql() throws Exception {
        sql.sql("ALTER VIEW perfiles_flota RENAME TO perfiles_no_disponibles").update();
        try {
            http.perform(get(RUTA).header("Authorization", "Bearer campo")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DESP_ERROR_OPERACION_NO_DISPONIBLE"))
                    .andExpect(content().string(not(containsString("SELECT"))));
        } finally {
            sql.sql("ALTER VIEW perfiles_no_disponibles RENAME TO perfiles_flota").update();
        }
    }
    private UUID crearPerfil() throws Exception {
        http.perform(post("/api/v1/repartidores").header("Authorization", "Bearer gestor").contentType("application/json")
                .content(GestionFlotaHttpTests.NUEVO_REPARTIDOR)).andExpect(status().isCreated());
        return sql.sql("SELECT id FROM repartidores").query(UUID.class).single();
    }
    private void vincular(UUID id) throws Exception {
        http.perform(put("/api/v1/repartidores/" + id + "/vinculacion").header("Authorization", "Bearer gestor")
                .contentType("application/json").content("{\"usuarioId\":\"" + GestionFlotaHttpTests.ACTOR + "\"}"))
                .andExpect(status().isNoContent());
    }
    private void acceso(String token, int estado) throws Exception {
        http.perform(get(RUTA).header("Authorization", "Bearer " + token)).andExpect(status().is(estado));
    }
    private Jwt token(String tipo, UUID sujeto, List<String> roles) {
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject(sujeto.toString()).claim("tipo", tipo)
                .claim("roles", roles).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }
    @RestController
    @org.springframework.boot.test.context.TestComponent
    static class ControladorCampoDePrueba {
        @GetMapping(RUTA)
        String comprobar() { return "Perfil local habilitado"; }
    }
}
