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

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Proveedor y PostgreSQL reales con migración Flyway; decodificador JWT identificado como doble. */
@SpringBootTest(properties = "SEGURIDAD_URL=http://127.0.0.1:1")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConsultaIncidenciasOperativasHttpTests {

    private static final String RUTA = "/internal/v1/operacion/consulta-incidencias";
    private static final String SCOPE = "operacion:incidencias:leer";
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @MockitoBean JwtDecoder decodificador;

    @BeforeEach
    void preparar() {
        sql.sql("TRUNCATE asignaciones_diarias, repartidores, furgonetas").update();
        sql.sql("""
                INSERT INTO repartidores (id, nombres, apellidos, dni, telefono, correo, numero_brevete,
                    turno_habitual, estado_registro, estado_vinculacion, creado_por, actualizado_por)
                VALUES (:id, 'Nombre', 'Histórico', '00000001', '000000000', 'ficticio@example.invalid',
                    'PRUEBA', 'COMPLETO', 'INACTIVO', 'PENDIENTE', :actor, :actor)
                """).param("id", id(1)).param("actor", id(999)).update();
        sql.sql("""
                INSERT INTO furgonetas (id, placa, estado, capacidad_max_kg, capacidad_max_m3, max_paquetes, creado_por, actualizado_por)
                VALUES (:id, 'DEMO-001', 'DISPONIBLE', 100, 2, 10, :actor, :actor)
                """).param("id", id(21)).param("actor", id(999)).update();
        for (int numero : List.of(31, 32)) {
            sql.sql("""
                    INSERT INTO asignaciones_diarias (id, repartidor_id, furgoneta_id, zona_id_externa, fecha_jornada,
                        capacidad_max_kg, capacidad_max_m3, max_paquetes, estado, abierta_por, cerrada_en)
                    VALUES (:id, :repartidor, :furgoneta, :zona, CURRENT_DATE, 100, 2, 10, :estado, :actor, :cierre)
                    """).param("id", id(numero)).param("repartidor", id(1)).param("furgoneta", id(21))
                    .param("zona", id(99)).param("actor", id(999)).param("estado", numero == 31 ? "CERRADA" : "ACTIVA")
                    .param("cierre", numero == 31 ? java.time.OffsetDateTime.parse("2026-10-15T23:00:00Z") : null).update();
        }
        when(decodificador.decode("servicio")).thenReturn(token("servicio", "modulo-despacho", SCOPE));
        when(decodificador.decode("gestor")).thenReturn(token("acceso", id(999).toString(), SCOPE));
        when(decodificador.decode("sin-scope")).thenReturn(token("servicio", "modulo-despacho", "otro"));
        when(decodificador.decode("otro-cliente")).thenReturn(token("servicio", "modulo-ventas", SCOPE));
    }

    @Test
    void leeHistoricosYJornadasEnOrdenSinExponerContactoNiInventarDatos() throws Exception {
        String cuerpo = "{\"idsRepartidor\":[\"" + id(1) + "\",\"REP-AUSENTE\"],\"idsJornada\":[\"JOR-AUSENTE\",\"" + id(31) + "\",\"" + id(32) + "\"]}";
        http.perform(post(RUTA).header("Authorization", "Bearer servicio").contentType("application/json").content(cuerpo))
                .andExpect(status().isOk()).andExpect(jsonPath("$.repartidores.length()").value(2))
                .andExpect(jsonPath("$.repartidores[0].nombre").value("Nombre Histórico"))
                .andExpect(jsonPath("$.repartidores[1].idRepartidor").value("REP-AUSENTE"))
                .andExpect(jsonPath("$.repartidores[1].nombre").value(nullValue()))
                .andExpect(jsonPath("$.repartidores[0].telefono").doesNotExist())
                .andExpect(jsonPath("$.repartidores[0].correo").doesNotExist())
                .andExpect(jsonPath("$.jornadas[0].estado").value("DESCONOCIDA"))
                .andExpect(jsonPath("$.jornadas[1].estado").value("CERRADA"))
                .andExpect(jsonPath("$.jornadas[1].cerradaEn").value("2026-10-15T23:00:00Z"))
                .andExpect(jsonPath("$.jornadas[2].estado").value("ACTIVA"))
                .andExpect(jsonPath("$.jornadas[2].cerradaEn").value(nullValue()))
                .andExpect(jsonPath("$.consultadoEn").isNotEmpty());
    }

    @Test
    void autorizaAntesDeLeerValidaLotesYNormalizaUnaCaidaDeDatos() throws Exception {
        sql.sql("ALTER TABLE repartidores RENAME TO repartidores_no_disponible").update();
        try {
            http.perform(post(RUTA).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            for (String token : List.of("gestor", "sin-scope", "otro-cliente")) {
                http.perform(post(RUTA).header("Authorization", "Bearer " + token).contentType("application/json").content("{}"))
                        .andExpect(status().isForbidden());
            }
            for (String cuerpo : List.of("{}", "{\"idsRepartidor\":[],\"idsJornada\":[]}",
                    "{\"idsRepartidor\":[\"a\",\"a\"],\"idsJornada\":[]}",
                    "{\"idsRepartidor\":[1],\"idsJornada\":[]}",
                    "{\"idsRepartidor\":[\" \"],\"idsJornada\":[]}",
                    "{\"idsRepartidor\":[\"a\"],\"idsJornada\":[],\"extra\":true}",
                    "{\"idsRepartidor\":null,\"idsJornada\":[]}",
                    "{\"idsRepartidor\":[" + String.join(",", java.util.stream.IntStream.range(0, 101).mapToObj(n -> "\"id-" + n + "\"").toList()) + "],\"idsJornada\":[]}")) {
                http.perform(post(RUTA).header("Authorization", "Bearer servicio").contentType("application/json").content(cuerpo))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_CAMPOS_INVALIDOS"));
            }
            http.perform(post(RUTA).header("Authorization", "Bearer servicio").contentType("application/json")
                            .content("{\"idsRepartidor\":[\"" + id(1) + "\"],\"idsJornada\":[]}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DESP_ERROR_OPERACION_NO_DISPONIBLE"))
                    .andExpect(content().string(not(containsString("SELECT"))));
        } finally {
            sql.sql("ALTER TABLE repartidores_no_disponible RENAME TO repartidores").update();
        }
    }

    private Jwt token(String tipo, String sujeto, String scope) {
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject(sujeto).claim("tipo", tipo)
                .claim("roles", List.of("GESTOR_DESPACHO")).claim("scope", scope).audience(List.of("api-despacho"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    private static UUID id(long numero) { return new UUID(0, numero); }
}
