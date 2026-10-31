package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Gestión §6.1 con PostgreSQL, Flyway y JWT RS256 reales: máquina de estados, intentos, historial y eventos. */
@Import({TestcontainersConfiguration.class, TransicionesHttpTests.RelojPruebas.class})
@SpringBootTest(properties = "despacho.programacion.recuperacion-habilitada=false")
@AutoConfigureMockMvc
class TransicionesHttpTests {
    static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    static final RSAKey CLAVE = clave();
    static final HttpServer SEGURIDAD = servidor();
    static final String URL = "http://127.0.0.1:" + SEGURIDAD.getAddress().getPort();
    static final Instant ASIGNADO_EN = Instant.parse("2026-10-17T13:12:00Z");
    static final Instant AHORA = Instant.parse("2026-10-17T15:41:00Z");
    static final String REPARTIDOR = "7d0f4a52-1b39-4c0a-9a0e-2f5e1b6c8d11";
    static final String JORNADA = "d42d8d4b-1a80-481f-9f35-9ff0c6c935f1";
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    UUID despacho;

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojPruebas {
        @Bean @Primary Clock relojF03() { return Clock.fixed(AHORA, ZoneOffset.UTC); }
    }
    @DynamicPropertySource static void configurar(DynamicPropertyRegistry propiedades) {
        propiedades.add("despacho.seguridad.url", () -> URL);
        propiedades.add("despacho.operacion.url", () -> URL);
        propiedades.add("despacho.operacion.token-servicio", () -> "tecnico-operacion");
    }
    @BeforeEach void preparar() {
        sql.sql("TRUNCATE despachos, claves_idempotencia CASCADE").update();
        despacho = UUID.randomUUID();
        sql.sql("""
                INSERT INTO despachos(id, pedido_id, codigo_operativo, codigo_rastreo_interno, estado, es_simulado, destinatario_nombre,
                    destinatario_telefono, direccion, referencia, distrito, peso_kg, volumen_m3, cantidad_paquetes, fecha_programada,
                    repartidor_id_externo, asignacion_diaria_id_externa, reserva_capacidad_id_externa, secuencia_ruta, numero_intentos, version)
                VALUES (:id, 'PED-F03', 'DSP-900', 'TRK-78904', 'ASIGNADO', false, 'Carlos Quispe', '+51987654321',
                    'Jr. Pumacahua 1180', 'Frente al parque', 'Jesús María', 2, 0.02, 1, '2026-10-17',
                    :repartidor, :jornada, 'f3a1d2c4-5b6e-4f70-8a91-b2c3d4e5f607', 2, 0, 3)
                """).param("id", despacho).param("repartidor", REPARTIDOR).param("jornada", JORNADA).update();
        sql.sql("""
                INSERT INTO historial_estados_despacho(despacho_id, tipo_operacion, estado_anterior, estado_nuevo, funcionalidad_origen, registrado_en)
                VALUES (:id, 'TRANSICION', 'PENDIENTE_ASIGNACION', 'ASIGNADO', 'F-02', :en)
                """).param("id", despacho).param("en", ASIGNADO_EN.atOffset(ZoneOffset.UTC)).update();
    }
    @AfterAll static void detener() { SEGURIDAD.stop(0); }

    @Test void inicioDeTrasladoRegistraHistorialProyeccionConHitosYEventoParaVentas() throws Exception {
        String clave = UUID.randomUUID().toString();
        String original = transicionar(clave, cuerpo("EN_CAMINO", null, null, null)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.estado").value("EN_CAMINO")).andExpect(jsonPath("$.estadoAnterior").value("ASIGNADO"))
                .andExpect(jsonPath("$.version").value(4)).andExpect(jsonPath("$.numeroIntentos").value(0))
                .andExpect(jsonPath("$.hitos.asignadoEn").value("2026-10-17T13:12:00Z"))
                .andExpect(jsonPath("$.hitos.enCaminoEn").value("2026-10-17T15:41:00Z"))
                .andReturn().getResponse().getContentAsString();
        assertThat(texto("SELECT estado FROM despachos")).isEqualTo("EN_CAMINO");
        assertThat(texto("SELECT funcionalidad_origen || ':' || ejecutor_id || ':' || origen FROM historial_estados_despacho WHERE estado_nuevo='EN_CAMINO'"))
                .isEqualTo("F-03:" + REPARTIDOR + ":MANUAL");
        JsonNode proyeccion = JSON.readTree(texto("SELECT payload::text FROM eventos_salientes WHERE destino='OPERACION'"));
        assertThat(proyeccion.path("estado").asText()).isEqualTo("EN_CAMINO");
        assertThat(proyeccion.path("hitos").path("enCaminoEn").asText()).isEqualTo("2026-10-17T15:41:00Z");
        assertThat(proyeccion.path("maximoIntentos").asInt()).isEqualTo(2);
        assertThat(texto("SELECT tipo_evento FROM eventos_salientes WHERE destino='VENTAS'")).isEqualTo("DESPACHO_EN_CAMINO");

        transicionar(clave, cuerpo("EN_CAMINO", null, null, null)).andExpect(status().isOk()).andExpect(content().json(original));
        transicionar(clave, cuerpo("ENTREGADO", UUID.randomUUID().toString(), null, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_OPERACION_REUTILIZADA"));
        assertThat(contar("historial_estados_despacho")).isEqualTo(2);
        assertThat(contar("eventos_salientes")).isEqualTo(2);
    }

    @Test void entregaExigeFotoYRegistraElIntentoConReceptor() throws Exception {
        transicionar(UUID.randomUUID().toString(), cuerpo("EN_CAMINO", null, null, null)).andExpect(status().isOk());
        transicionar(UUID.randomUUID().toString(), cuerpo("ENTREGADO", null, null, null)).andExpect(status().isBadRequest());
        String evidencia = UUID.randomUUID().toString();
        var solicitud = cuerpo("ENTREGADO", evidencia, null, null); solicitud.put("recibidoPor", "Carlos Quispe");
        transicionar(UUID.randomUUID().toString(), solicitud).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENTREGADO")).andExpect(jsonPath("$.hitos.entregadoEn").value("2026-10-17T15:41:00Z"));
        assertThat(texto("SELECT resultado || ':' || nombre_receptor || ':' || evidencia_id_externa FROM intentos_entrega"))
                .isEqualTo("ENTREGADO:Carlos Quispe:" + evidencia);
        assertThat(texto("SELECT ultima_incidencia_id::text FROM despachos")).isNull();
        JsonNode venta = JSON.readTree(texto("SELECT payload::text FROM eventos_salientes WHERE tipo_evento='DESPACHO_ENTREGADO'"));
        assertThat(venta.path("datos").path("recibidoPor").asText()).isEqualTo("Carlos Quispe");
        JsonNode liberacion = JSON.readTree(texto("SELECT payload::text FROM eventos_salientes WHERE destino='OPERACION_LIBERACION'"));
        assertThat(liberacion.path("idReserva").asText()).isEqualTo("f3a1d2c4-5b6e-4f70-8a91-b2c3d4e5f607");
        assertThat(liberacion.path("motivo").asText()).isEqualTo("ENTREGA");
    }

    @Test void falloConsumeIntentoYDejaLaIncidenciaVigenteParaF04() throws Exception {
        transicionar(UUID.randomUUID().toString(), cuerpo("EN_CAMINO", null, null, null)).andExpect(status().isOk());
        String evidencia = UUID.randomUUID().toString();
        transicionar(UUID.randomUUID().toString(), cuerpo("FALLIDO", evidencia, "CLIENTE_AUSENTE", "Toqué el timbre dos veces"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.numeroIntentos").value(1))
                .andExpect(jsonPath("$.motivoFallo").value("CLIENTE_AUSENTE"));
        assertThat(texto("SELECT numero_intentos::text FROM despachos")).isEqualTo("1");
        assertThat(texto("SELECT (ultima_incidencia_id = i.id)::text FROM despachos d JOIN intentos_entrega i ON i.despacho_id = d.id"))
                .isEqualTo("true");
        assertThat(texto("SELECT motivo_codigo || ':' || consume_intento || ':' || comentario FROM intentos_entrega"))
                .isEqualTo("CLIENTE_AUSENTE:true:Toqué el timbre dos veces");
        JsonNode venta = JSON.readTree(texto("SELECT payload::text FROM eventos_salientes WHERE tipo_evento='DESPACHO_FALLIDO'"));
        assertThat(venta.path("datos").path("numeroIntento").asInt()).isEqualTo(1);
        assertThat(contar("eventos_salientes WHERE destino='OPERACION_LIBERACION'")).as("FALLIDO conserva la reserva hasta F-04").isZero();
    }

    @Test void noIntentadoDelCierreNoConsumeIntentoNiRequiereFotoDesdeAsignado() throws Exception {
        transicionar(UUID.randomUUID().toString(), cuerpo("FALLIDO", null, "NO_INTENTADO", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.numeroIntentos").value(0)).andExpect(jsonPath("$.hitos.fallidoEn").value("2026-10-17T15:41:00Z"));
        assertThat(texto("SELECT consume_intento::text FROM intentos_entrega")).isEqualTo("false");
        assertThat(texto("SELECT ultima_incidencia_id IS NOT NULL FROM despachos")).isEqualTo("t");
    }

    @Test void rechazaOrigenIncompatibleRutaAjenaYMotivosNoSeleccionables() throws Exception {
        transicionar(UUID.randomUUID().toString(), cuerpo("ENTREGADO", UUID.randomUUID().toString(), null, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_ESTADO_INCOMPATIBLE"));
        var ajena = cuerpo("EN_CAMINO", null, null, null); ajena.put("idRepartidor", UUID.randomUUID().toString());
        transicionar(UUID.randomUUID().toString(), ajena).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DESPACHO_REASIGNADO"));
        transicionar(UUID.randomUUID().toString(), cuerpo("EN_CAMINO", null, null, null)).andExpect(status().isOk());
        sql.sql("UPDATE motivos_fallo SET activo = false WHERE codigo = 'ZONA_INACCESIBLE'").update();
        try {
            transicionar(UUID.randomUUID().toString(), cuerpo("FALLIDO", UUID.randomUUID().toString(), "ZONA_INACCESIBLE", null))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_MOTIVO_INVALIDO"));
        } finally { sql.sql("UPDATE motivos_fallo SET activo = true WHERE codigo = 'ZONA_INACCESIBLE'").update(); }
        assertThat(texto("SELECT estado FROM despachos")).isEqualTo("EN_CAMINO");
        assertThat(contar("intentos_entrega")).isZero();
    }

    @Test void soloElClienteTecnicoDelModuloConSuScopeAccedeALasRutasInternas() throws Exception {
        http.perform(post("/internal/v1/despachos/" + despacho + "/transiciones").header("Authorization", "Bearer " + humano())
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                .content(JSON.writeValueAsString(cuerpo("EN_CAMINO", null, null, null)))).andExpect(status().isForbidden());
        http.perform(post("/internal/v1/despachos/" + despacho + "/transiciones").header("Authorization", "Bearer " + servicio("modulo-ventas", "gestion:despachos:transicionar"))
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                .content(JSON.writeValueAsString(cuerpo("EN_CAMINO", null, null, null)))).andExpect(status().isForbidden());
        http.perform(get("/internal/v1/catalogos/motivos-fallo").header("Authorization", "Bearer " + servicio("modulo-despacho", "gestion:catalogos:leer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.motivos[?(@.codigo=='NO_INTENTADO')].seleccionableEnCampo").value(false));
        assertThat(texto("SELECT estado FROM despachos")).isEqualTo("ASIGNADO");
    }

    private ResultActions transicionar(String clave, Map<String, Object> cuerpo) throws Exception {
        return http.perform(post("/internal/v1/despachos/" + despacho + "/transiciones")
                .header("Authorization", "Bearer " + servicio("modulo-despacho", "gestion:despachos:transicionar"))
                .header("Idempotency-Key", clave).contentType("application/json").content(JSON.writeValueAsString(cuerpo)));
    }
    private static Map<String, Object> cuerpo(String destino, String evidencia, String motivo, String comentario) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("estadoDestino", destino); cuerpo.put("idRepartidor", REPARTIDOR); cuerpo.put("idJornada", JORNADA);
        cuerpo.put("idEvidencia", evidencia); cuerpo.put("motivo", motivo); cuerpo.put("comentario", comentario);
        cuerpo.put("ejecutor", REPARTIDOR); cuerpo.put("origen", "MANUAL");
        return cuerpo;
    }
    private String texto(String consulta) { return sql.sql(consulta).query(String.class).optional().orElse(null); }
    private long contar(String tabla) { return sql.sql("SELECT count(*) FROM " + tabla).query(Long.class).single(); }
    private static String servicio(String cliente, String scope) { return jwt("servicio", cliente, scope, List.of(), "api-despacho"); }
    private static String humano() { return jwt("acceso", REPARTIDOR, null, List.of("GESTOR_DESPACHO"), null); }
    private static String jwt(String tipo, String sub, String scope, List<String> roles, String audiencia) {
        try {
            var claims = new JWTClaimsSet.Builder().subject(sub).issuer(URL + "/api/v1/auth").issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("tipo", tipo).claim("roles", roles);
            if (scope != null) claims.claim("scope", scope);
            if (audiencia != null) claims.audience(audiencia);
            var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(CLAVE.getKeyID()).build(), claims.build());
            token.sign(new RSASSASigner(CLAVE)); return token.serialize();
        } catch (JOSEException error) { throw new IllegalStateException(error); }
    }
    private static RSAKey clave() {
        try { return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate(); }
        catch (JOSEException error) { throw new IllegalStateException(error); }
    }
    private static HttpServer servidor() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            servidor.createContext("/", solicitud -> {
                String ruta = solicitud.getRequestURI().getPath();
                if (ruta.equals("/api/v1/auth/.well-known/openid-configuration")) responder(solicitud, 200,
                        JSON.writeValueAsString(Map.of("issuer", URL + "/api/v1/auth", "jwks_uri", URL + "/api/v1/auth/.well-known/jwks.json")));
                else if (ruta.equals("/api/v1/auth/.well-known/jwks.json")) responder(solicitud, 200,
                        JSON.writeValueAsString(Map.of("keys", List.of(CLAVE.toPublicJWK().toJSONObject()))));
                else responder(solicitud, 404, "{}");
            });
            servidor.start(); return servidor;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    private static void responder(HttpExchange solicitud, int estado, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8); solicitud.getResponseHeaders().set("Content-Type", "application/json");
        solicitud.sendResponseHeaders(estado, bytes.length); solicitud.getResponseBody().write(bytes); solicitud.close();
    }
}
