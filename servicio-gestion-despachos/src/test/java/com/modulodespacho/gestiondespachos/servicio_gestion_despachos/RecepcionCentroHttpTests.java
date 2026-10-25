package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL/PostGIS, Flyway, transacciones y HTTP de Gestión reales; Seguridad y Operación son dobles. */
@SpringBootTest(properties = {"SEGURIDAD_URL=http://127.0.0.1:1",
        "despacho.programacion.recuperacion-habilitada=false", "despacho.recepcion.procesador-habilitado=false"})
@Import({TestcontainersConfiguration.class, RecepcionCentroHttpTests.RelojPruebas.class})
@AutoConfigureMockMvc
class RecepcionCentroHttpTests {
    static final Instant AHORA = Instant.parse("2026-10-24T15:00:00Z");
    static final String DESPACHO = id(1), INCIDENCIA = id(2), ACTOR = id(3);
    static final AtomicInteger LLAMADAS = new AtomicInteger();
    static final AtomicInteger ESTADO_REMOTO = new AtomicInteger(200);
    static final AtomicReference<String> RESERVA = new AtomicReference<>();
    static final AtomicReference<String> AUTORIZACION = new AtomicReference<>();
    static final HttpServer OPERACION = servidor();
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired JsonMapper json;
    @Autowired RecepcionCentroJdbc recepciones;
    @Autowired LiberacionesCapacidadJdbc pendientes;
    @Autowired LiberadorCapacidad liberador;
    @Autowired DetalleIncidenciaJdbc detalle;
    @MockitoBean JwtDecoder decodificador;

    @TestConfiguration static class RelojPruebas {
        @Bean @Primary Clock relojRecepcion() { return Clock.fixed(AHORA, ZoneOffset.UTC); }
    }
    @DynamicPropertySource static void configurar(DynamicPropertyRegistry propiedades) {
        propiedades.add("despacho.operacion.url", () -> "http://127.0.0.1:" + OPERACION.getAddress().getPort());
        propiedades.add("despacho.operacion.token-servicio", () -> "credencial-tecnica");
    }
    @BeforeEach void preparar() {
        sql.sql("TRUNCATE despachos CASCADE").update();
        sql.sql("""
                INSERT INTO motivos_fallo (codigo, etiqueta, seleccionable_en_campo, consume_intento, activo)
                VALUES ('NO_INTENTADO', 'No intentado', false, false, true) ON CONFLICT (codigo) DO NOTHING
                """).update();
        sql.sql("""
                INSERT INTO despachos (id, pedido_id, estado, codigo_operativo, codigo_rastreo_interno, version, numero_intentos, pedido_anulado_en)
                VALUES (CAST(:id AS uuid), 'PED-PRUEBA', 'FALLIDO', 'DSP-PRUEBA', 'TRK-PRUEBA', 8, 2, now())
                """).param("id", DESPACHO).update();
        sql.sql("""
                INSERT INTO intentos_entrega (id, despacho_id, resultado, motivo_codigo, consume_intento,
                    repartidor_id_externo, asignacion_diaria_id_externa, reserva_capacidad_id_externa)
                VALUES (CAST(:inc AS uuid), CAST(:des AS uuid), 'FALLIDO', 'NO_INTENTADO', false,
                    'repartidor-original', 'jornada-original', 'reserva-original')
                """).param("inc", INCIDENCIA).param("des", DESPACHO).update();
        sql.sql("UPDATE despachos SET ultima_incidencia_id = CAST(:inc AS uuid) WHERE id::text = :des")
                .param("inc", INCIDENCIA).param("des", DESPACHO).update();
        sql.sql("""
                INSERT INTO historial_estados_despacho (despacho_id, tipo_operacion, estado_anterior, estado_nuevo,
                    funcionalidad_origen, ejecutor_tipo, ejecutor_id, origen, datos)
                VALUES (CAST(:id AS uuid), 'TRANSICION', 'EN_CAMINO', 'FALLIDO', 'F-03', 'USUARIO', :actor, 'MANUAL', '{}')
                """).param("id", DESPACHO).param("actor", ACTOR).update();
        when(decodificador.decode("gestor")).thenReturn(token(ACTOR, "acceso", List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("gestor2")).thenReturn(token(id(4), "acceso", List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("otro")).thenReturn(token(ACTOR, "acceso", List.of("OTRO")));
        when(decodificador.decode("servicio")).thenReturn(token(ACTOR, "servicio", List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("invalido")).thenThrow(new BadJwtException("Token rechazado"));
        LLAMADAS.set(0); ESTADO_REMOTO.set(200); RESERVA.set(null); AUTORIZACION.set(null);
    }
    @Test void aceptaSelloAlteradoSinObservacionYConsultaSinEfectosRemotos() throws Exception {
        confirmar("gestor", cuerpo(false, null)).andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.estado").value("FALLIDO")).andExpect(jsonPath("$.versionDespacho").value(9))
                .andExpect(jsonPath("$.recepcion.idUsuario").value(ACTOR)).andExpect(jsonPath("$.recepcion.selloIntacto").value(false))
                .andExpect(jsonPath("$.recepcion.observacion").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.recepcion.liberacionCapacidad.estado").value("PENDIENTE"));
        assertThat(numero("SELECT count(*) FROM liberaciones_capacidad")).isEqualTo(1);
        assertThat(numero("SELECT count(*) FROM historial_estados_despacho WHERE tipo_operacion='RECEPCION_EN_CENTRO'")).isEqualTo(1);
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isZero();
        assertThat(numero("SELECT numero_intentos FROM despachos")).isEqualTo(2);
        var consulta = detalle.consultar(DESPACHO);
        assertThat(consulta.incidencia().recepcion().estado()).isEqualTo("RECIBIDO_EN_CENTRO");
        assertThat(consulta.incidencia().recepcion().liberacionCapacidad().estado()).isEqualTo("PENDIENTE");
        // Estos GET pueden consultar datos operativos, pero jamás la liberación.
        http.perform(get("/api/v1/despachos/" + DESPACHO + "/incidencia").header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.incidencia.recepcion.selloIntacto").value(false));
        assertThat(LLAMADAS.get()).isZero();
    }
    @Test void respuestaOriginalSobreviveLiberacionResolucionYNuevaSalida() throws Exception {
        String original = confirmar("gestor", cuerpo(true, "  recibido\n  ")).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        procesar(AHORA);
        assertThat(LLAMADAS.get()).isEqualTo(1);
        assertThat(AUTORIZACION.get()).isEqualTo("Bearer credencial-tecnica");
        assertThat(detalle.consultar(DESPACHO).incidencia().recepcion().liberacionCapacidad().estado()).isEqualTo("CONFIRMADA");
        // Otra salida utiliza referencias diferentes; el replay no debe buscarlas ni tocarlas.
        sql.sql("""
                INSERT INTO intentos_entrega (id, despacho_id, resultado, motivo_codigo,
                    repartidor_id_externo, asignacion_diaria_id_externa, reserva_capacidad_id_externa)
                VALUES (CAST(:inc AS uuid), CAST(:des AS uuid), 'FALLIDO', 'NO_INTENTADO', 'otro', 'jornada-nueva', 'reserva-nueva')
                """).param("inc", id(20)).param("des", DESPACHO).update();
        sql.sql("UPDATE despachos SET estado='ASIGNADO', version=12, ultima_incidencia_id=CAST(:inc AS uuid), reserva_capacidad_id_externa='reserva-nueva'")
                .param("inc", id(20)).update();
        var repetida = confirmar("gestor", cuerpo(true, "recibido")).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(repetida)).isEqualTo(json.readTree(original));
        assertThat(RESERVA.get()).isEqualTo("reserva-original");
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(12);
        assertThat(numero("SELECT count(*) FROM liberaciones_capacidad")).isEqualTo(1);
        assertThat(numero("SELECT count(*) FROM historial_estados_despacho WHERE tipo_operacion='RECEPCION_EN_CENTRO'")).isEqualTo(1);
    }
    @Test void rechazaReutilizacionPorOtroActorOCuerpoPeroNormalizaEspacios() throws Exception {
        confirmar("gestor", cuerpo(false, " recibido ")).andExpect(status().isAccepted());
        confirmar("gestor", cuerpo(false, "recibido")).andExpect(status().isAccepted());
        confirmar("gestor2", cuerpo(false, "recibido")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_OPERACION_REUTILIZADA"));
        confirmar("gestor", cuerpo(true, "recibido")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_OPERACION_REUTILIZADA"));
        confirmar("gestor", cuerpo(false, "recibido").replace("\"versionDespacho\":8", "\"versionDespacho\":9"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_RECEPCION_YA_CONFIRMADA"));
        assertThat(sql.sql("SELECT recibido_por::text FROM intentos_entrega").query(String.class).single()).isEqualTo(ACTOR);
    }
    @Test void validaAutorizacionAntesDeExistenciaYFormato() throws Exception {
        http.perform(post("/api/v1/despachos/no-existe/recepcion-centro").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        confirmar("invalido", "{}").andExpect(status().isUnauthorized());
        for (String token : List.of("otro", "servicio")) confirmar(token, "{}").andExpect(status().isForbidden());
        http.perform(post("/api/v1/despachos/no-existe/recepcion-centro").header("Authorization", "Bearer gestor")
                .contentType("application/json").content(cuerpo(true, null))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DESPACHO_NO_ENCONTRADO"));
        assertThat(numero("SELECT count(*) FROM liberaciones_capacidad")).isZero();
    }
    @Test void validaEstadoVersionIncidenciaYReferenciasSinEscribir() throws Exception {
        confirmar("gestor", cuerpo(true, null).replace(INCIDENCIA, id(99))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DESPACHO_DESACTUALIZADO"));
        confirmar("gestor", cuerpo(true, null).replace("\"versionDespacho\":8", "\"versionDespacho\":7"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_DESPACHO_DESACTUALIZADO"));
        sql.sql("UPDATE despachos SET estado='ENTREGADO'").update();
        confirmar("gestor", cuerpo(true, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_ESTADO_INCOMPATIBLE"));
        sql.sql("UPDATE despachos SET estado='FALLIDO'").update();
        sql.sql("UPDATE intentos_entrega SET reserva_capacidad_id_externa=NULL").update();
        confirmar("gestor", cuerpo(true, null)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_INCONSISTENTES"));
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(8);
        assertThat(numero("SELECT count(*) FROM liberaciones_capacidad")).isZero();
    }
    @Test void rechazaTiposYParametrosSinMutacion() throws Exception {
        for (String contenido : List.of("{}", cuerpo(true, null).replace("true", "\"true\""),
                cuerpo(true, null).replace("\"versionDespacho\":8", "\"versionDespacho\":8.0"),
                cuerpo(true, null).replace("}", ",\"usuario\":\"ajeno\"}"), cuerpo(false, "😀".repeat(501)))) {
            confirmar("gestor", contenido).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_RECEPCION_INVALIDOS"))
                    .andExpect(jsonPath("$.errores").isNotEmpty());
        }
        http.perform(post(ruta()).header("Authorization", "Bearer gestor").queryParam("extra", "1")
                .contentType("application/json").content(cuerpo(true, null))).andExpect(status().isBadRequest());
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(8);
    }
    @Test void revierteTodasLasEscriturasAnteFalloEnCadaTabla() throws Exception {
        for (String tabla : List.of("intentos_entrega", "despachos", "historial_estados_despacho", "liberaciones_capacidad")) {
            sql.sql("ALTER TABLE " + tabla + " ADD CONSTRAINT fallo_prueba CHECK(false) NOT VALID").update();
            try {
                confirmar("gestor", cuerpo(true, "recepción")).andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("DESP_ERROR_RECEPCION_NO_DISPONIBLE"));
            } finally { sql.sql("ALTER TABLE " + tabla + " DROP CONSTRAINT fallo_prueba").update(); }
            assertThat(numero("SELECT version FROM despachos")).isEqualTo(8);
            assertThat(numero("SELECT count(*) FROM intentos_entrega WHERE recibido_en_centro_en IS NOT NULL")).isZero();
            assertThat(numero("SELECT count(*) FROM liberaciones_capacidad")).isZero();
            assertThat(numero("SELECT count(*) FROM historial_estados_despacho WHERE tipo_operacion='RECEPCION_EN_CENTRO'")).isZero();
        }
        assertThat(LLAMADAS.get()).isZero();
    }
    @Test void dosTransaccionesConMismaIdentidadDevuelvenUnaSolaRecepcion() throws Exception {
        carrera("gestor", false, List.of(202, 202));
    }
    @Test void dosGestoresConcurrentesNoSobrescribenLaPrimeraRecepcion() throws Exception {
        carrera("gestor2", true, List.of(202, 409));
    }
    @Test void dosContenidosConcurrentesDelMismoGestorNoSobrescribenLaRecepcion() throws Exception {
        carrera("gestor", true, List.of(202, 409));
    }
    @Test void respuestaRemotaPerdidaSeRecuperaSinTocarLaReservaNueva() throws Exception {
        confirmar("gestor", cuerpo(true, null)).andExpect(status().isAccepted());
        ESTADO_REMOTO.set(503);
        procesar(AHORA);
        assertThat(numero("SELECT intentos_envio FROM liberaciones_capacidad")).isEqualTo(1);
        assertThat(sql.sql("SELECT proximo_intento_en FROM liberaciones_capacidad").query(OffsetDateTime.class).single().toInstant()).isEqualTo(AHORA.plusSeconds(30));
        // Se pierde el proceso tras efectuar el HTTP remoto, antes de confirmar su resultado local.
        var reclamada = pendientes.reclamar(AHORA.plusSeconds(30)).orElseThrow();
        ESTADO_REMOTO.set(200);
        assertThat(liberador.liberar(reclamada).confirmada()).isTrue();
        sql.sql("UPDATE despachos SET reserva_capacidad_id_externa='reserva-nueva'").update();
        assertThat(pendientes.reclamar(AHORA.plusSeconds(89))).isEmpty();
        procesar(AHORA.plusSeconds(91));
        assertThat(RESERVA.get()).isEqualTo("reserva-original");
        assertThat(numero("SELECT intentos_envio FROM liberaciones_capacidad")).isEqualTo(3);
        assertThat(sql.sql("SELECT estado FROM liberaciones_capacidad").query(String.class).single()).isEqualTo("CONFIRMADA");
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(9);
    }
    @Test void reclamacionesExcluyentesYTokenAntiguoNoPuedeFinalizar() throws Exception {
        confirmar("gestor", cuerpo(true, null)).andExpect(status().isAccepted());
        var primera = pendientes.reclamar(AHORA).orElseThrow();
        assertThat(new LiberacionesCapacidadJdbc(sql).reclamar(AHORA)).isEmpty();
        var segunda = new LiberacionesCapacidadJdbc(sql).reclamar(AHORA.plusSeconds(61)).orElseThrow();
        pendientes.confirmar(primera, AHORA.plusSeconds(62), AHORA);
        pendientes.reintentar(primera, AHORA.plusSeconds(62), AHORA.plusSeconds(70), false, "VIEJO");
        assertThat(sql.sql("SELECT token_procesamiento FROM liberaciones_capacidad").query(UUID.class).single()).isEqualTo(segunda.token());
        pendientes.confirmar(segunda, AHORA.plusSeconds(62), AHORA);
        assertThat(sql.sql("SELECT estado FROM liberaciones_capacidad").query(String.class).single()).isEqualTo("CONFIRMADA");
    }
    @Test void erroresPermanentesMantienenAvisoYCalendarioHastaConfirmar() throws Exception {
        confirmar("gestor", cuerpo(true, null)).andExpect(status().isAccepted());
        ESTADO_REMOTO.set(403); procesar(AHORA);
        assertThat(detalle.consultar(DESPACHO).incidencia().recepcion().liberacionCapacidad().requiereRevision()).isTrue();
        ESTADO_REMOTO.set(503); procesar(AHORA.plusSeconds(3600));
        assertThat(sql.sql("SELECT proximo_intento_en FROM liberaciones_capacidad").query(OffsetDateTime.class).single().toInstant())
                .isEqualTo(AHORA.plusSeconds(7200));
        ESTADO_REMOTO.set(200); procesar(AHORA.plusSeconds(7200));
        assertThat(detalle.consultar(DESPACHO).incidencia().recepcion().liberacionCapacidad().requiereRevision()).isFalse();
    }
    @Test void datosHistoricosSinRespuestaOriginalVerificableNoDisparanLiberacion() throws Exception {
        confirmar("gestor", cuerpo(true, null)).andExpect(status().isAccepted());
        sql.sql("UPDATE liberaciones_capacidad SET respuesta_original='{}'").update();
        confirmar("gestor", cuerpo(true, null)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_INCONSISTENTES"));
        procesar(AHORA);
        assertThat(LLAMADAS.get()).isZero();
        assertThat(sql.sql("SELECT requiere_revision FROM liberaciones_capacidad").query(Boolean.class).single()).isTrue();
        assertThat(sql.sql("SELECT ultimo_codigo_error FROM liberaciones_capacidad").query(String.class).single())
                .isEqualTo("DATOS_RECEPCION_INCONSISTENTES");
    }
    private void carrera(String segundoActor, boolean otroContenido, List<Integer> esperados) throws Exception {
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            var inicio = new CountDownLatch(1);
            var uno = hilos.submit(() -> { inicio.await(); return confirmar("gestor", cuerpo(false, null)).andReturn().getResponse().getStatus(); });
            var dos = hilos.submit(() -> { inicio.await(); return confirmar(segundoActor, cuerpo(otroContenido, null)).andReturn().getResponse().getStatus(); });
            inicio.countDown();
            assertThat(List.of(uno.get(15, TimeUnit.SECONDS), dos.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrderElementsOf(esperados);
        }
        assertThat(numero("SELECT count(*) FROM liberaciones_capacidad")).isEqualTo(1);
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(9);
    }
    private void procesar(Instant ahora) {
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            var procesador = new ProcesadorLiberaciones(new LiberacionesCapacidadJdbc(sql), liberador,
                    Clock.fixed(ahora, ZoneOffset.UTC), hilos, List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1)));
            assertThat(procesador.procesarUna()).isTrue();
        }
    }
    private int numero(String consulta) { return sql.sql(consulta).query(Integer.class).single(); }
    private ResultActions confirmar(String token, String cuerpo) throws Exception {
        return http.perform(post(ruta()).header("Authorization", "Bearer " + token).contentType("application/json").content(cuerpo));
    }
    private String ruta() { return "/api/v1/despachos/" + DESPACHO + "/recepcion-centro"; }
    private String cuerpo(boolean sello, String observacion) {
        var cuerpo = new LinkedHashMap<String, Object>();
        cuerpo.put("idIncidencia", INCIDENCIA); cuerpo.put("versionDespacho", 8); cuerpo.put("selloIntacto", sello); cuerpo.put("observacion", observacion);
        return json.writeValueAsString(cuerpo);
    }
    private static String id(long valor) { return new UUID(0, valor).toString(); }
    private Jwt token(String actor, String tipo, List<String> roles) {
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject(actor).claim("tipo", tipo)
                .claim("roles", roles).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }
    private static HttpServer servidor() {
        try {
            var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/internal/v1/capacidad/reservas/", intercambio -> {
                LLAMADAS.incrementAndGet();
                RESERVA.set(intercambio.getRequestURI().getPath().split("/")[5]);
                AUTORIZACION.set(intercambio.getRequestHeaders().getFirst("Authorization"));
                int estado = ESTADO_REMOTO.get();
                byte[] respuesta = (estado == 200 ? "{\"idReserva\":\"" + RESERVA.get() + "\",\"idDespacho\":\"" + DESPACHO
                        + "\",\"estado\":\"LIBERADA\",\"motivoLiberacion\":\"RECEPCION_EN_CENTRO\",\"liberadaEn\":\"2026-10-24T15:00:00Z\"}" : "{}").getBytes(StandardCharsets.UTF_8);
                intercambio.getResponseHeaders().add("Content-Type", "application/json");
                intercambio.sendResponseHeaders(estado, respuesta.length);
                try (var salida = intercambio.getResponseBody()) { salida.write(respuesta); }
                intercambio.close();
            });
            servidor.start(); return servidor;
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @AfterAll static void cerrar() { OPERACION.stop(0); }
}
