package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
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
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.application.GestorFlota;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL y transacciones reales; únicamente el decodificador JWT se sustituye por un doble. */
@SpringBootTest(properties = "SEGURIDAD_URL=http://127.0.0.1:1")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class GestionFlotaHttpTests {
    static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000999");
    static final String CONTACTO = """
            {"telefono":"+51 999-111-222","correo":"kevin@example.invalid","numeroBrevete":"BRE-001","turnoHabitual":"COMPLETO"}
            """;
    static final String NUEVO_REPARTIDOR = """
            {"nombres":"Kevin","apellidos":"Prueba","dni":"12345678","telefono":"999111222",
             "correo":"kevin@example.invalid","numeroBrevete":"BRE-001","turnoHabitual":"COMPLETO"}
            """;
    static final String NUEVA_FURGONETA = """
            {"placa":"abc-123","capacidadPesoKg":100.125,"capacidadVolumenM3":2.1234,"maximoPaquetes":10}
            """;
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired DataSource origen;
    @Autowired GestorFlota gestor;
    @MockitoBean JwtDecoder decodificador;

    @BeforeEach
    void preparar() {
        sql.sql("TRUNCATE auditoria_configuracion, proyecciones_despacho, reservas_capacidad, asignaciones_diarias, repartidores, furgonetas").update();
        when(decodificador.decode("gestor")).thenReturn(token("acceso", ACTOR.toString(), List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("otro")).thenReturn(token("acceso", ACTOR.toString(), List.of("ADMIN_SISTEMA")));
        when(decodificador.decode("servicio")).thenReturn(token("servicio", ACTOR.toString(), List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("sin-identidad")).thenReturn(token("acceso", "no-es-uuid", List.of("GESTOR_DESPACHO")));
    }
    @Test
    void registraConsultaEditaYDaDeBajaConAuditoriaAtomica() throws Exception {
        UUID repartidor = crearRepartidor();
        http.perform(get("/api/v1/repartidores/" + repartidor).header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dni").value("12345678"))
                .andExpect(jsonPath("$.estadoRegistro").value("ACTIVO")).andExpect(jsonPath("$.estadoVinculacion").value("PENDIENTE"));
        http.perform(put("/api/v1/repartidores/" + repartidor).header("Authorization", "Bearer gestor")
                .contentType("application/json").content(CONTACTO.replace("COMPLETO", "TARDE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turnoHabitual").value("TARDE"))
                .andExpect(jsonPath("$.nombres").value("Kevin"));
        http.perform(get("/api/v1/repartidores?pagina=1&tamanoPagina=1&turnoHabitual=TARDE&estadoVinculacion=PENDIENTE")
                .header("Authorization", "Bearer gestor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1)).andExpect(jsonPath("$.contenido.length()").value(1))
                .andExpect(jsonPath("$.totalPaginas").value(1));
        http.perform(get("/api/v1/repartidores?pagina=2&tamanoPagina=1").header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contenido.length()").value(0));
        baja(repartidor, 204); baja(repartidor, 204);
        assertThat(sql.sql("SELECT count(*) FROM auditoria_configuracion WHERE usuario_id = :actor")
                .param("actor", ACTOR).query(Long.class).single()).isEqualTo(3);
        assertThat(sql.sql("SELECT estado_registro FROM repartidores").query(String.class).single()).isEqualTo("INACTIVO");
        http.perform(patch("/api/v1/repartidores/" + repartidor + "/estado").header("Authorization", "Bearer gestor")
                .contentType("application/json").content("{\"estado\":\"ACTIVO\"}")).andExpect(status().isBadRequest());
    }
    @Test
    void impideDuplicadosYVinculosCruzadosSinCrearCuentasGlobales() throws Exception {
        UUID repartidor = crearRepartidor();
        http.perform(post("/api/v1/repartidores").header("Authorization", "Bearer gestor").contentType("application/json")
                .content(NUEVO_REPARTIDOR)).andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("kevin@example.invalid"))));
        http.perform(post("/api/v1/repartidores").header("Authorization", "Bearer gestor").contentType("application/json")
                .content(NUEVO_REPARTIDOR.replace("12345678", "87654321").replace("kevin@", "KEVIN@"))).andExpect(status().isConflict());
        UUID usuario = UUID.randomUUID();
        vincular(repartidor, usuario, 204); vincular(repartidor, usuario, 204);
        vincular(repartidor, UUID.randomUUID(), 409);
        UUID segundo = crearOtroRepartidor();
        vincular(segundo, usuario, 409);
        assertThat(sql.sql("SELECT count(*) FROM auditoria_configuracion WHERE accion = 'VINCULACION'").query(Long.class).single()).isEqualTo(1);
        baja(segundo, 204); vincular(segundo, UUID.randomUUID(), 409);
    }
    @Test
    void validaPermisosIdentidadCamposYParametrosAntesDeEscribir() throws Exception {
        http.perform(post("/api/v1/repartidores").contentType("application/json").content(NUEVO_REPARTIDOR)).andExpect(status().isUnauthorized());
        for (String token : List.of("otro", "servicio", "sin-identidad")) {
            http.perform(post("/api/v1/repartidores").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(NUEVO_REPARTIDOR)).andExpect(status().isForbidden());
        }
        for (String cuerpo : List.of("{}", NUEVO_REPARTIDOR.replace("12345678", "12"),
                NUEVO_REPARTIDOR.replace("999111222", "--- ---"), NUEVO_REPARTIDOR.replace("kevin@example.invalid", "invalido"),
                NUEVO_REPARTIDOR.replace("\"COMPLETO\"", "null"), NUEVO_REPARTIDOR.replace("\"COMPLETO\"", "\"NOCHE\""),
                NUEVO_REPARTIDOR.replace("\"Kevin\"", "\" \""),
                NUEVO_REPARTIDOR.replace("\"nombres\"", "\"campoAjeno\""))) {
            http.perform(post("/api/v1/repartidores").header("Authorization", "Bearer gestor").contentType("application/json").content(cuerpo))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_CAMPOS_INVALIDOS"));
        }
        for (String consulta : List.of("pagina=0", "tamanoPagina=101", "pagina=abc", "estadoRegistro=OTRO", "turnoHabitual=NOCHE")) {
            http.perform(get("/api/v1/repartidores?" + consulta).header("Authorization", "Bearer gestor")).andExpect(status().isBadRequest());
        }
        http.perform(get("/api/v1/repartidores/no-uuid").header("Authorization", "Bearer gestor")).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/repartidores/" + UUID.randomUUID()).header("Authorization", "Bearer gestor")).andExpect(status().isNotFound());
        assertThat(sql.sql("SELECT count(*) FROM repartidores").query(Long.class).single()).isZero();
    }
    @Test
    void administraCapacidadesPlacaInmutableYTransiciones() throws Exception {
        UUID furgoneta = crearFurgoneta();
        http.perform(post("/api/v1/furgonetas").header("Authorization", "Bearer gestor").contentType("application/json")
                .content(NUEVA_FURGONETA.replace("abc-123", "ABC-123"))).andExpect(status().isConflict());
        http.perform(get("/api/v1/furgonetas?placa=bc-1&estado=DISPONIBLE").header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contenido[0].placa").value("ABC-123"))
                .andExpect(jsonPath("$.contenido[0].capacidadVolumenM3").value(2.1234));
        http.perform(get("/api/v1/furgonetas?placa=%25").header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(0));
        http.perform(put("/api/v1/furgonetas/" + furgoneta).header("Authorization", "Bearer gestor").contentType("application/json")
                .content("{\"capacidadPesoKg\":50,\"capacidadVolumenM3\":1,\"maximoPaquetes\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.placa").value("ABC-123"));
        cambiar(furgoneta, "EN_MANTENIMIENTO", 204);
        cambiar(furgoneta, "FUERA_DE_SERVICIO", 204);
        cambiar(furgoneta, "EN_MANTENIMIENTO", 409);
        cambiar(furgoneta, "DISPONIBLE", 204);
        cambiar(furgoneta, "DISPONIBLE", 204);
        assertThat(sql.sql("SELECT count(*) FROM auditoria_configuracion").query(Long.class).single()).isEqualTo(5);
        for (String cuerpo : List.of(NUEVA_FURGONETA.replace("100.125", "0"), NUEVA_FURGONETA.replace("100.125", "100.1234"),
                NUEVA_FURGONETA.replace("2.1234", "2.12345"), NUEVA_FURGONETA.replace("\"maximoPaquetes\":10", "\"maximoPaquetes\":1.5"),
                NUEVA_FURGONETA.replace("\"maximoPaquetes\":10", "\"maximoPaquetes\":32768"))) {
            http.perform(post("/api/v1/furgonetas").header("Authorization", "Bearer gestor").contentType("application/json").content(cuerpo))
                    .andExpect(status().isBadRequest());
        }
    }
    @Test
    void protegeCargaReservadaYDespachosYCierraJornadaVaciaSinCambiarSuCapacidad() throws Exception {
        UUID repartidor = crearRepartidor(); vincular(repartidor, UUID.randomUUID(), 204);
        UUID furgoneta = crearFurgoneta();
        UUID jornada = abrirJornada(repartidor, furgoneta);
        UUID reserva = UUID.randomUUID(), despacho = UUID.randomUUID();
        sql.sql("""
                INSERT INTO reservas_capacidad (id, clave_asignacion, despacho_id_externo, asignacion_diaria_id,
                    peso_kg, volumen_m3, cantidad_paquetes, estado)
                VALUES (:id, :clave, :despacho, :jornada, 1, 1, 1, 'ACTIVA')
                """).param("id", reserva).param("clave", UUID.randomUUID()).param("despacho", despacho).param("jornada", jornada).update();
        baja(repartidor, 409); cambiar(furgoneta, "EN_MANTENIMIENTO", 409);
        sql.sql("UPDATE reservas_capacidad SET estado='LIBERADA'").update();
        sql.sql("INSERT INTO proyecciones_despacho (despacho_id_externo, asignacion_diaria_id, estado) VALUES (:despacho,:jornada,'EN_CAMINO')")
                .param("despacho", despacho).param("jornada", jornada).update();
        baja(repartidor, 409); cambiar(furgoneta, "FUERA_DE_SERVICIO", 409);
        sql.sql("UPDATE proyecciones_despacho SET estado='ENTREGADO'").update();
        http.perform(put("/api/v1/furgonetas/" + furgoneta).header("Authorization", "Bearer gestor").contentType("application/json")
                .content("{\"capacidadPesoKg\":50,\"capacidadVolumenM3\":1,\"maximoPaquetes\":5}")).andExpect(status().isOk());
        assertThat(sql.sql("SELECT capacidad_max_kg FROM asignaciones_diarias").query(Integer.class).single()).isEqualTo(100);
        cambiar(furgoneta, "EN_MANTENIMIENTO", 204);
        assertThat(sql.sql("SELECT estado FROM asignaciones_diarias").query(String.class).single()).isEqualTo("CERRADA");
        assertThat(sql.sql("SELECT cerrada_en IS NOT NULL FROM asignaciones_diarias").query(Boolean.class).single()).isTrue();
        comprobarAuditoriaCierre("MANTENIMIENTO");
        cambiar(furgoneta, "EN_MANTENIMIENTO", 204);
        comprobarAuditoriaCierre("MANTENIMIENTO");
        assertThatThrownBy(() -> sql.sql("UPDATE proyecciones_despacho SET estado='ASIGNADO'").update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        baja(repartidor, 204);
        assertThatThrownBy(() -> abrirJornada(repartidor, furgoneta)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test
    void revierteMutacionSiFallaLaAuditoriaSinExponerSql() throws Exception {
        UUID repartidor = crearRepartidor();
        sql.sql("ALTER TABLE auditoria_configuracion RENAME TO auditoria_no_disponible").update();
        try {
            http.perform(put("/api/v1/repartidores/" + repartidor).header("Authorization", "Bearer gestor").contentType("application/json")
                    .content(CONTACTO.replace("COMPLETO", "TARDE"))).andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(not(containsString("INSERT"))));
            assertThat(sql.sql("SELECT turno_habitual FROM repartidores").query(String.class).single()).isEqualTo("COMPLETO");
        } finally { sql.sql("ALTER TABLE auditoria_no_disponible RENAME TO auditoria_configuracion").update(); }
    }
    @Test
    void serializaRetiroConUnaReservaConcurrente() throws Exception {
        UUID repartidor = crearRepartidor(); vincular(repartidor, UUID.randomUUID(), 204);
        UUID furgoneta = crearFurgoneta(), jornada = abrirJornada(repartidor, furgoneta);
        try (var conexion = origen.getConnection(); var ejecutor = Executors.newSingleThreadExecutor()) {
            conexion.setAutoCommit(false);
            try (var sentencia = conexion.prepareStatement("""
                    INSERT INTO reservas_capacidad (clave_asignacion, despacho_id_externo, asignacion_diaria_id,
                        peso_kg, volumen_m3, cantidad_paquetes, estado) VALUES (?, ?, ?, 1, 1, 1, 'ACTIVA')
                    """)) {
                sentencia.setObject(1, UUID.randomUUID()); sentencia.setObject(2, UUID.randomUUID()); sentencia.setObject(3, jornada);
                sentencia.executeUpdate();
            }
            CountDownLatch inicio = new CountDownLatch(1);
            Future<?> retiro = ejecutor.submit(() -> {
                inicio.countDown();
                gestor.cambiarEstado(furgoneta,
                        com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.DatosFlota.EstadoFurgoneta.EN_MANTENIMIENTO, ACTOR);
            });
            assertThat(inicio.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> retiro.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            conexion.commit();
            assertThatThrownBy(() -> retiro.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.ConflictoFlota.class);
        }
        assertThat(sql.sql("SELECT estado FROM furgonetas").query(String.class).single()).isEqualTo("DISPONIBLE");
    }
    @Test
    void rechazaEdicionDeIdentidadYRecursosAusentesSinAuditar() throws Exception {
        UUID repartidor = crearRepartidor(), furgoneta = crearFurgoneta(), ausente = UUID.randomUUID();
        for (String campo : List.of("\"dni\":\"87654321\"", "\"nombres\":\"Otro\"", "\"estadoRegistro\":\"INACTIVO\"")) {
            http.perform(put("/api/v1/repartidores/" + repartidor).header("Authorization", "Bearer gestor")
                    .contentType("application/json").content(CONTACTO.strip().replaceFirst("\\}$", "," + campo + "}")))
                    .andExpect(status().isBadRequest());
        }
        http.perform(put("/api/v1/furgonetas/" + furgoneta).header("Authorization", "Bearer gestor")
                .contentType("application/json").content("""
                    {"placa":"OTRA-001","capacidadPesoKg":20,"capacidadVolumenM3":1,"maximoPaquetes":2}
                    """)).andExpect(status().isBadRequest());
        http.perform(put("/api/v1/repartidores/" + ausente).header("Authorization", "Bearer gestor")
                .contentType("application/json").content(CONTACTO)).andExpect(status().isNotFound());
        http.perform(put("/api/v1/furgonetas/" + ausente).header("Authorization", "Bearer gestor")
                .contentType("application/json").content("""
                    {"capacidadPesoKg":20,"capacidadVolumenM3":1,"maximoPaquetes":2}
                    """)).andExpect(status().isNotFound());
        baja(ausente, 404);
        cambiar(ausente, "EN_MANTENIMIENTO", 404);
        vincular(ausente, UUID.randomUUID(), 404);
        assertThat(sql.sql("SELECT count(*) FROM auditoria_configuracion").query(Long.class).single()).isEqualTo(2);
        assertThat(sql.sql("SELECT dni FROM repartidores").query(String.class).single()).isEqualTo("12345678");
        assertThat(sql.sql("SELECT placa FROM furgonetas").query(String.class).single()).isEqualTo("ABC-123");
    }

    @Test
    void validaVinculacionManualSinAceptarCredencialesYConservaEstadoPendiente() throws Exception {
        UUID repartidor = crearRepartidor();
        for (String cuerpo : List.of("{}", "{\"usuarioId\":null}", "{\"usuarioId\":\"invalido\"}",
                "{\"usuarioId\":\"" + UUID.randomUUID() + "\",\"password\":\"no-transmitir\"}")) {
            http.perform(put("/api/v1/repartidores/" + repartidor + "/vinculacion").header("Authorization", "Bearer gestor")
                    .contentType("application/json").content(cuerpo)).andExpect(status().isBadRequest());
        }
        assertThat(sql.sql("SELECT estado_vinculacion FROM repartidores").query(String.class).single()).isEqualTo("PENDIENTE");
        assertThat(sql.sql("SELECT id_usuario_seguridad IS NULL FROM repartidores").query(Boolean.class).single()).isTrue();
        assertThat(sql.sql("SELECT count(*) FROM auditoria_configuracion").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void exigeGestorEnTodasLasOperacionesDeCatalogo() throws Exception {
        UUID id = UUID.randomUUID();
        for (String token : List.of("otro", "servicio")) {
            for (String ruta : List.of("/api/v1/repartidores", "/api/v1/repartidores/" + id, "/api/v1/furgonetas")) {
                http.perform(get(ruta).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            }
            http.perform(post("/api/v1/furgonetas").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(NUEVA_FURGONETA)).andExpect(status().isForbidden());
            http.perform(put("/api/v1/repartidores/" + id).header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(CONTACTO)).andExpect(status().isForbidden());
            http.perform(put("/api/v1/repartidores/" + id + "/vinculacion").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content("{\"usuarioId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isForbidden());
            for (String ruta : List.of("/api/v1/repartidores/" + id + "/estado", "/api/v1/furgonetas/" + id + "/estado")) {
                http.perform(patch(ruta).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"estado\":\"INACTIVO\"}")).andExpect(status().isForbidden());
            }
            http.perform(put("/api/v1/furgonetas/" + id).header("Authorization", "Bearer " + token)
                    .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        }
        assertThat(sql.sql("SELECT count(*) FROM auditoria_configuracion").query(Long.class).single()).isZero();
    }

    @Test
    void conservaPrecisionExtremaYNormalizaFiltrosSinFabricarResultados() throws Exception {
        http.perform(post("/api/v1/furgonetas").header("Authorization", "Bearer gestor").contentType("application/json")
                .content("""
                    {"placa":"MAX-001","capacidadPesoKg":9999999.999,"capacidadVolumenM3":999999.9999,"maximoPaquetes":32767}
                    """)).andExpect(status().isCreated()).andExpect(jsonPath("$.maximoPaquetes").value(32767));
        assertThat(sql.sql("SELECT capacidad_max_kg::text FROM furgonetas").query(String.class).single()).isEqualTo("9999999.999");
        assertThat(sql.sql("SELECT capacidad_max_m3::text FROM furgonetas").query(String.class).single()).isEqualTo("999999.9999");
        http.perform(get("/api/v1/furgonetas?placa=ausente").header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contenido.length()").value(0))
                .andExpect(jsonPath("$.totalElementos").value(0)).andExpect(jsonPath("$.totalPaginas").value(0));
        http.perform(get("/api/v1/repartidores").header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pagina").value(1))
                .andExpect(jsonPath("$.tamanoPagina").value(50)).andExpect(jsonPath("$.totalPaginas").value(0));
        for (String consulta : List.of("estado=DESCONOCIDO", "placa=ABCDEFGHIJK", "pagina=0", "tamanoPagina=101")) {
            http.perform(get("/api/v1/furgonetas?" + consulta).header("Authorization", "Bearer gestor"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void exportaRespuestasRealesParaVerificarElConsumidorFrontend() throws Exception {
        UUID repartidor = crearRepartidor();
        String perfil = http.perform(get("/api/v1/repartidores/" + repartidor).header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String vehiculo = http.perform(post("/api/v1/furgonetas").header("Authorization", "Bearer gestor")
                .contentType("application/json").content(NUEVA_FURGONETA)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String perfiles = http.perform(get("/api/v1/repartidores").header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String vehiculos = http.perform(get("/api/v1/furgonetas").header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String error = http.perform(post("/api/v1/furgonetas").header("Authorization", "Bearer gestor")
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/f05-respuestas-backend.json"),
                "{\"repartidor\":" + perfil + ",\"furgoneta\":" + vehiculo + ",\"paginaRepartidores\":" + perfiles
                        + ",\"paginaFurgonetas\":" + vehiculos + ",\"errorCampos\":" + error + "}");
    }

    @Test
    void bajaCierraLaJornadaVaciaEImpideAbrirOtraConElVehiculoDisponible() throws Exception {
        UUID repartidor = crearRepartidor();
        vincular(repartidor, UUID.randomUUID(), 204);
        UUID furgoneta = crearFurgoneta();
        abrirJornada(repartidor, furgoneta);
        baja(repartidor, 204);
        assertThat(sql.sql("SELECT estado FROM asignaciones_diarias").query(String.class).single()).isEqualTo("CERRADA");
        assertThat(sql.sql("SELECT cerrada_en IS NOT NULL FROM asignaciones_diarias").query(Boolean.class).single()).isTrue();
        comprobarAuditoriaCierre("GESTOR_DESPACHO");
        baja(repartidor, 204);
        comprobarAuditoriaCierre("GESTOR_DESPACHO");
        assertThat(sql.sql("SELECT estado FROM furgonetas").query(String.class).single()).isEqualTo("DISPONIBLE");
        assertThatThrownBy(() -> abrirJornada(repartidor, furgoneta))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(sql.sql("SELECT count(*) FROM asignaciones_diarias").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void revierteBajaYRetiroSiNoPuedeAuditarElCierreIndirecto() throws Exception {
        UUID repartidor = crearRepartidor();
        vincular(repartidor, UUID.randomUUID(), 204);
        UUID furgoneta = crearFurgoneta();
        abrirJornada(repartidor, furgoneta);
        sql.sql("ALTER TABLE auditoria_configuracion ADD CONSTRAINT prueba_auditoria_cierre CHECK (entidad <> 'asignaciones_diarias')").update();
        try {
            cambiar(furgoneta, "EN_MANTENIMIENTO", 409);
            baja(repartidor, 409);
            assertThat(sql.sql("SELECT estado FROM asignaciones_diarias").query(String.class).single()).isEqualTo("ACTIVA");
            assertThat(sql.sql("SELECT cerrada_en IS NULL FROM asignaciones_diarias").query(Boolean.class).single()).isTrue();
            assertThat(sql.sql("SELECT estado FROM furgonetas").query(String.class).single()).isEqualTo("DISPONIBLE");
            assertThat(sql.sql("SELECT estado_registro FROM repartidores").query(String.class).single()).isEqualTo("ACTIVO");
        } finally {
            sql.sql("ALTER TABLE auditoria_configuracion DROP CONSTRAINT prueba_auditoria_cierre").update();
        }
    }

    private void comprobarAuditoriaCierre(String tipo) {
        assertThat(sql.sql("""
                SELECT count(*) FROM auditoria_configuracion a
                JOIN asignaciones_diarias j ON a.entidad_id = j.id::text
                WHERE a.entidad='asignaciones_diarias' AND a.accion='CIERRE_JORNADA'
                  AND a.usuario_id=:actor AND a.datos_anteriores->>'estado'='ACTIVA'
                  AND a.datos_nuevos->>'estado'='CERRADA'
                  AND a.datos_nuevos->>'tipo_cierre'=:tipo
                  AND a.datos_nuevos->>'cerrada_en' IS NOT NULL
                """).param("actor", ACTOR).param("tipo", tipo).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void registraRendimientoLocalDePaginasDeCincuentaSinAcreditarLaNube() throws Exception {
        sql.sql("""
                INSERT INTO repartidores (nombres,apellidos,dni,telefono,correo,numero_brevete,turno_habitual,
                    estado_registro,estado_vinculacion,creado_por,actualizado_por)
                SELECT 'Carga','Sintética',lpad(serie::text,8,'0'),'999111222',
                    'carga-' || serie || '@example.invalid','PER-' || serie,'COMPLETO','ACTIVO','PENDIENTE',:actor,:actor
                FROM generate_series(1,100) AS serie
                """).param("actor", ACTOR).update();
        sql.sql("""
                INSERT INTO furgonetas (placa,estado,capacidad_max_kg,capacidad_max_m3,max_paquetes,creado_por,actualizado_por)
                SELECT 'PER-' || lpad(serie::text,3,'0'),'DISPONIBLE',500,4,80,:actor,:actor
                FROM generate_series(1,100) AS serie
                """).param("actor", ACTOR).update();
        java.util.Map<String, Double> percentiles = new java.util.LinkedHashMap<>();
        for (String recurso : List.of("repartidores", "furgonetas")) {
            java.util.List<Double> muestras = new java.util.ArrayList<>();
            for (int iteracion = 0; iteracion < 25; iteracion++) {
                long inicio = System.nanoTime();
                http.perform(get("/api/v1/" + recurso).header("Authorization", "Bearer gestor"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.contenido.length()").value(50))
                        .andExpect(jsonPath("$.totalElementos").value(100)).andExpect(jsonPath("$.totalPaginas").value(2));
                if (iteracion >= 5) muestras.add((System.nanoTime() - inicio) / 1_000_000.0);
            }
            muestras.sort(Double::compare);
            percentiles.put(recurso, muestras.get(18));
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/f05-rendimiento-local.json"),
                "{\"entorno\":\"MockMvc y PostgreSQL aislado; JWT doble; sin red de Render\","
                + "\"registrosPorCatalogo\":100,\"tamanoPagina\":50,\"calentamiento\":5,\"muestras\":20,"
                + "\"p95RepartidoresMs\":" + percentiles.get("repartidores")
                + ",\"p95FurgonetasMs\":" + percentiles.get("furgonetas") + "}");
    }

    UUID crearRepartidor() throws Exception {
        http.perform(post("/api/v1/repartidores").header("Authorization", "Bearer gestor").contentType("application/json")
                .content(NUEVO_REPARTIDOR)).andExpect(status().isCreated()).andExpect(header().exists("Location"));
        return sql.sql("SELECT id FROM repartidores WHERE dni='12345678'").query(UUID.class).single();
    }
    UUID crearOtroRepartidor() throws Exception {
        http.perform(post("/api/v1/repartidores").header("Authorization", "Bearer gestor").contentType("application/json")
                .content(NUEVO_REPARTIDOR.replace("12345678","87654321").replace("kevin@", "otro@"))).andExpect(status().isCreated());
        return sql.sql("SELECT id FROM repartidores WHERE dni='87654321'").query(UUID.class).single();
    }
    UUID crearFurgoneta() throws Exception {
        http.perform(post("/api/v1/furgonetas").header("Authorization", "Bearer gestor").contentType("application/json")
                .content(NUEVA_FURGONETA)).andExpect(status().isCreated()).andExpect(jsonPath("$.placa").value("ABC-123"));
        return sql.sql("SELECT id FROM furgonetas").query(UUID.class).single();
    }
    UUID abrirJornada(UUID repartidor, UUID furgoneta) {
        UUID jornada = UUID.randomUUID();
        sql.sql("""
                INSERT INTO asignaciones_diarias (id,repartidor_id,furgoneta_id,zona_id_externa,fecha_jornada,
                    capacidad_max_kg,capacidad_max_m3,max_paquetes,estado,abierta_por)
                VALUES (:id,:repartidor,:furgoneta,:zona,CURRENT_DATE,100,2,10,'ACTIVA',:actor)
                """).param("id", jornada).param("repartidor", repartidor).param("furgoneta", furgoneta)
                .param("zona", UUID.randomUUID()).param("actor", ACTOR).update();
        return jornada;
    }
    void baja(UUID id, int estado) throws Exception {
        http.perform(patch("/api/v1/repartidores/" + id + "/estado").header("Authorization", "Bearer gestor")
                .contentType("application/json").content("{\"estado\":\"INACTIVO\"}")).andExpect(status().is(estado));
    }
    void cambiar(UUID id, String nuevo, int estado) throws Exception {
        http.perform(patch("/api/v1/furgonetas/" + id + "/estado").header("Authorization", "Bearer gestor")
                .contentType("application/json").content("{\"estado\":\"" + nuevo + "\"}")).andExpect(status().is(estado));
    }
    void vincular(UUID id, UUID usuario, int estado) throws Exception {
        http.perform(put("/api/v1/repartidores/" + id + "/vinculacion").header("Authorization", "Bearer gestor")
                .contentType("application/json").content("{\"usuarioId\":\"" + usuario + "\"}")).andExpect(status().is(estado));
    }
    private Jwt token(String tipo, String sujeto, List<String> roles) {
        return Jwt.withTokenValue("prueba").header("alg","RS256").subject(sujeto).claim("tipo",tipo)
                .claim("roles",roles).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }
}
