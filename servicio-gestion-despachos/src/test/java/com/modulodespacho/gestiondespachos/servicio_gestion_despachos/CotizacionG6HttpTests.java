package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.productos.infrastructure.ClienteDatosFisicosHttp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** G6 es un doble HTTP explícito; endpoint, cliente, transacciones y PostgreSQL son reales. */
@Import({TestcontainersConfiguration.class, CotizacionG6HttpTests.RelojPruebas.class})
@SpringBootTest(properties = {"SEGURIDAD_URL=http://127.0.0.1:1", "spring.flyway.enabled=true",
        "despacho.productos.token-servicio=credencial-propia-g6", "despacho.cotizacion.limite-por-minuto=10000"})
@AutoConfigureMockMvc
class CotizacionG6HttpTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger ESTADO = new AtomicInteger(200);
    private static final AtomicReference<String> CUERPO = new AtomicReference<>("AUTO");
    private static final AtomicReference<String> COMERCIAL = new AtomicReference<>("ACTIVO");
    private static final List<JsonNode> CONSULTAS = new CopyOnWriteArrayList<>();
    private static final List<String> CREDENCIALES = new CopyOnWriteArrayList<>();
    private static final HttpServer SERVIDOR = iniciar();
    private UUID zona;

    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @MockitoBean JwtDecoder decodificador;
    @MockitoSpyBean(name = "productosProgramacion") ClienteDatosFisicosHttp productos;

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojPruebas {
        @Bean @org.springframework.context.annotation.Primary
        Clock relojCotizacion() { return Clock.fixed(Instant.parse("2026-11-06T17:00:00Z"), ZoneOffset.UTC); }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry propiedades) {
        propiedades.add("despacho.productos.url", () -> "http://127.0.0.1:" + SERVIDOR.getAddress().getPort());
    }

    @AfterAll static void cerrar() { SERVIDOR.stop(0); }

    @BeforeEach
    void preparar() {
        sql.sql("TRUNCATE zonas CASCADE").update();
        zona = UUID.randomUUID();
        sql.sql("INSERT INTO zonas(id,nombre,estado) VALUES (:id,'Zona G6','ACTIVO')").param("id", zona).update();
        sql.sql("INSERT INTO zonas_distritos(zona_id,distrito_id) SELECT :id,id FROM distrito WHERE nombre='San Borja'")
                .param("id", zona).update();
        sql.sql("""
                INSERT INTO tarifas_zona(zona_id,tarifa_base,peso_base_kg,recargo_kg_adicional,
                    factor_volumetrico,moneda,plazo_min_dias,plazo_max_dias)
                VALUES (:id,8.12,3,2,1.25,'PEN',2,2)
                """).param("id", zona).update();
        when(decodificador.decode("canal")).thenReturn(Jwt.withTokenValue("canal").header("alg", "RS256")
                .subject("modulo-marketplace").claim("tipo", "servicio").claim("scope", "cotizaciones:calcular")
                .audience(List.of("api-despacho")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
        doAnswer(invocacion -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocacion.callRealMethod();
        }).when(productos).obtener(any());
        ESTADO.set(200); CUERPO.set("AUTO"); COMERCIAL.set("ACTIVO"); CONSULTAS.clear(); CREDENCIALES.clear();
    }

    @Test
    void perfilNormalConsultaG6ConIdentidadPropiaYConfirmaLaInstantanea() throws Exception {
        String respuesta = cotizar("[{\"sku\":\"001-a\",\"cantidad\":2},{\"sku\":\"001-a\",\"cantidad\":3}]")
                .andExpect(status().isOk()).andExpect(jsonPath("$.costoEnvio").value(8.50))
                .andExpect(jsonPath("$.volumenTotalM3").value(0.006))
                .andExpect(jsonPath("$.fechaEstimadaEntrega").value("2026-11-10"))
                .andReturn().getResponse().getContentAsString();
        assertThat(CREDENCIALES).containsExactly("Bearer credencial-propia-g6");
        assertThat(CONSULTAS).hasSize(1);
        assertThat(CONSULTAS.getFirst().path("skus").size()).isEqualTo(1);
        JsonNode detalle = JSON.readTree(sql.sql("SELECT detalle::text FROM cotizaciones").query(String.class).single());
        JsonNode salida = JSON.readTree(respuesta);
        for (String campo : List.of("coberturaDisponible", "tipoCotizacion", "nombreZona", "distrito", "pesoTotalKg",
                "volumenTotalM3", "costoEnvio", "moneda", "plazoEstimadoDiasHabiles", "fechaEstimadaEntrega", "cotizadoEn")) {
            assertThat(detalle.path("resultado").path(campo)).as(campo).isEqualTo(salida.path(campo));
        }
        assertThat(detalle.path("lineas").get(0).path("cantidad").asInt()).isEqualTo(5);
        assertThat(detalle.path("lineas").get(0).path("perfil").path("actualizadoEn").asText()).isEqualTo("2026-11-05T12:00:00Z");
        assertThat(sql.sql("SELECT count(*) FROM cotizaciones WHERE pedido_id IS NULL AND despacho_id IS NULL AND vence_en IS NULL AND estado='vigente' AND NOT es_simulado")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(sql.sql("SELECT total FROM cotizaciones").query(BigDecimal.class).single()).isEqualByComparingTo("8.50");
        assertThat(sql.sql("SELECT peso_kg FROM cotizaciones").query(BigDecimal.class).single()).isEqualByComparingTo("0.617283945617283945");
        assertThat(sql.sql("SELECT count(*) FROM despachos").query(Long.class).single()).isZero();
    }

    @Test
    void usaVolumenExactoParaCostoYRondaSoloTrasAcumular() throws Exception {
        sql.sql("UPDATE tarifas_zona SET tarifa_base=8,peso_base_kg=1,recargo_kg_adicional=5000,factor_volumetrico=1").update();
        CUERPO.set("{\"productos\":[" + perfil("A", "ACTIVO").replace("0.123456789123456789", "1").replace("\"alto\":11", "\"alto\":10").replace("\"largo\":10", "\"largo\":1").replace("\"ancho\":10", "\"ancho\":1") + "],\"noEncontrados\":[]}");
        cotizar("[{\"sku\":\"A\",\"cantidad\":1}]").andExpect(status().isOk())
                .andExpect(jsonPath("$.volumenTotalM3").value(0.001)).andExpect(jsonPath("$.costoEnvio").value(8.50));
        JsonNode detalle = JSON.readTree(sql.sql("SELECT detalle::text FROM cotizaciones").query(String.class).single());
        assertThat(detalle.path("costoExacto").decimalValue()).isEqualByComparingTo("8.05");
        assertThat(detalle.path("ajusteRedondeo").decimalValue()).isEqualByComparingTo("0.45");
        assertThat(detalle.path("totalesExactos").path("volumenTotalM3").decimalValue()).isEqualByComparingTo("0.00001");
    }

    @Test
    void repetirCreaInstantaneasIndependientesYLaTarifaHistoricaNoCambia() throws Exception {
        cotizar(linea("A")).andExpect(status().isOk());
        cotizar(linea("A")).andExpect(status().isOk());
        sql.sql("UPDATE tarifas_zona SET activa=false WHERE zona_id=:id").param("id", zona).update();
        sql.sql("INSERT INTO tarifas_zona(zona_id,tarifa_base,peso_base_kg,moneda,plazo_min_dias,plazo_max_dias) VALUES (:id,15.13,3,'PEN',2,2)")
                .param("id", zona).update();
        cotizar(linea("A")).andExpect(status().isOk()).andExpect(jsonPath("$.costoEnvio").value(15.50));
        assertThat(sql.sql("SELECT total FROM cotizaciones ORDER BY total").query(BigDecimal.class).list())
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("8.50"), new BigDecimal("8.50"), new BigDecimal("15.50"));
    }

    @Test
    void coberturaSinLineasSinTarifaYFueraDeCoberturaNoConsultanNiInsertan() throws Exception {
        http.perform(post("/api/v1/cotizaciones").header("Authorization", "Bearer canal").contentType("application/json")
                .content("{\"destino\":{\"distrito\":\"San Borja\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.costoEnvio").doesNotExist());
        sql.sql("UPDATE tarifas_zona SET activa=false").update();
        cotizar(linea("A")).andExpect(status().isOk()).andExpect(jsonPath("$.tipoCotizacion").doesNotExist());
        sql.sql("UPDATE zonas SET estado='INACTIVO'").update();
        cotizar("[]").andExpect(status().isOk()).andExpect(jsonPath("$.coberturaDisponible").value(false));
        assertThat(CONSULTAS).isEmpty(); assertSinFilas();
    }

    @Test
    void admiteCienUnicosYMuchasLineasRepetidasPeroRechazaCientoUnoSinHttp() throws Exception {
        String cien = JSON.writeValueAsString(IntStream.range(0, 100).mapToObj(i -> Map.of("sku", "SKU-" + i, "cantidad", 1)).toList());
        cotizar(cien).andExpect(status().isOk());
        assertThat(CONSULTAS.getFirst().path("skus").size()).isEqualTo(100);
        sql.sql("TRUNCATE cotizaciones").update(); CONSULTAS.clear();
        String demasiados = JSON.writeValueAsString(IntStream.range(0, 101).mapToObj(i -> Map.of("sku", "SKU-" + i, "cantidad", 1)).toList());
        cotizar(demasiados).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_LIMITE_SKUS_EXCEDIDO"));
        assertThat(CONSULTAS).isEmpty(); assertSinFilas();
        String repetidas = JSON.writeValueAsString(IntStream.range(0, 150).mapToObj(i -> Map.of("sku", "A", "cantidad", 1)).toList());
        cotizar(repetidas).andExpect(status().isOk());
        assertThat(CONSULTAS.getFirst().path("skus").size()).isEqualTo(1);
    }

    @Test
    void estadosEfectivosNoActivosRechazanTodoElLote() throws Exception {
        for (String estado : List.of("INACTIVO", "BORRADOR")) {
            COMERCIAL.set(estado);
            cotizar(linea("001-a")).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("DESP_ERROR_PRODUCTO_NO_ELEGIBLE"))
                    .andExpect(jsonPath("$.details.skusAfectados[0]").value("001-a"));
            assertSinFilas();
        }
    }

    @Test
    void padresYErroresFisicosPreservanSoloSusAfectadosSinCotizacionesParciales() throws Exception {
        ESTADO.set(422);
        for (String codigo : List.of("SKU_INVALIDO", "DATOS_FISICOS_INCOMPLETOS")) {
            CUERPO.set("{\"status\":422,\"code\":\"" + codigo + "\",\"details\":{\"skusAfectados\":[\"001-a\"]}}");
            cotizar("[{\"sku\":\"001-a\",\"cantidad\":1},{\"sku\":\"VALIDO\",\"cantidad\":1}]")
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.details.skusAfectados.length()").value(1))
                    .andExpect(jsonPath("$.details.skusAfectados[0]").value("001-a"));
            assertSinFilas();
        }
        CUERPO.set("{\"status\":422,\"code\":\"SKU_INVALIDO\",\"detail\":\"001-a\"}");
        cotizar(linea("001-a")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_RESPUESTA_PRODUCTOS_INVALIDA"))
                .andExpect(jsonPath("$.details").doesNotExist());
        assertSinFilas();
    }

    @Test
    void fallaInsercionYCommitSinRespuestaExitosaNiFilaParcial() throws Exception {
        sql.sql("ALTER TABLE cotizaciones ADD CONSTRAINT prueba_fallo CHECK (total < 0) NOT VALID").update();
        try { comprobarPersistenciaFallida(); }
        finally { sql.sql("ALTER TABLE cotizaciones DROP CONSTRAINT prueba_fallo").update(); }
        sql.sql("CREATE FUNCTION prueba_fallo_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Fallo diferido de prueba'; END $$").update();
        sql.sql("CREATE CONSTRAINT TRIGGER prueba_commit AFTER INSERT ON cotizaciones DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION prueba_fallo_commit()").update();
        try { comprobarPersistenciaFallida(); }
        finally {
            sql.sql("DROP TRIGGER prueba_commit ON cotizaciones").update();
            sql.sql("DROP FUNCTION prueba_fallo_commit()").update();
        }
        cotizar(linea("A")).andExpect(status().isOk());
    }

    @Test
    void respuestasInvalidasInexistentesYFallosTecnicosNoInsertan() throws Exception {
        CUERPO.set("{\"productos\":[],\"noEncontrados\":[]}");
        cotizar(linea("A")).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("DESP_ERROR_RESPUESTA_PRODUCTOS_INVALIDA"));
        CUERPO.set("{\"productos\":[],\"noEncontrados\":[\"A\"]}");
        cotizar(linea("A")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_PRODUCTO_NO_ENCONTRADO"))
                .andExpect(jsonPath("$.details.skusAfectados[0]").value("A"));
        ESTADO.set(503); CUERPO.set("{}");
        cotizar(linea("A")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_PRODUCTOS_NO_DISPONIBLE"));
        assertSinFilas();
    }

    @Test
    void permutacionesDeErroresMixtosConservanCodigoYAfectadosDelProveedor() throws Exception {
        ESTADO.set(422);
        for (List<String> orden : List.of(List.of("PADRE-01", "INCOMPLETO", "PADRE-02"),
                List.of("PADRE-02", "PADRE-01", "INCOMPLETO"))) {
            List<String> afectados = orden.stream().filter(sku -> sku.startsWith("PADRE-")).toList();
            CUERPO.set(JSON.writeValueAsString(Map.of("status", 422, "code", "SKU_INVALIDO", "details", Map.of("skusAfectados", afectados))));
            cotizar(JSON.writeValueAsString(orden.stream().map(sku -> Map.of("sku", sku, "cantidad", 1)).toList()))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DESP_ERROR_PRODUCTO_NO_ELEGIBLE"))
                    .andExpect(jsonPath("$.details.skusAfectados").value(org.hamcrest.Matchers.contains(afectados.toArray(String[]::new))));
            assertSinFilas();
        }
    }

    @Test
    void ignoraTotalesDelCanalYRechazaCantidadesAcumuladasFueraDeRango() throws Exception {
        http.perform(post("/api/v1/cotizaciones").header("Authorization", "Bearer canal").contentType("application/json")
                .content("{\"destino\":{\"distrito\":\"San Borja\"},\"pesoTotalKg\":9999,\"volumenTotalM3\":9999,\"lineas\":" + linea("A") + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.volumenTotalM3").value(0.002))
                .andExpect(jsonPath("$.costoEnvio").value(8.50));
        sql.sql("TRUNCATE cotizaciones").update(); CONSULTAS.clear();
        cotizar("[{\"sku\":\"A\",\"cantidad\":2147483647},{\"sku\":\"A\",\"cantidad\":1}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_CANTIDAD_INVALIDA"));
        assertThat(CONSULTAS).isEmpty(); assertSinFilas();
    }

    @Test
    void noConvierteSkuNumericosNiCantidadesFraccionariasOTextuales() throws Exception {
        for (String lineas : List.of("[{\"sku\":123,\"cantidad\":1}]", "[{\"sku\":\"A\",\"cantidad\":1.5}]",
                "[{\"sku\":\"A\",\"cantidad\":\"2\"}]", "[{\"sku\":\"A\",\"cantidad\":2147483648}]", "{}")) {
            cotizar(lineas).andExpect(status().isBadRequest());
        }
        assertThat(CONSULTAS).isEmpty(); assertSinFilas();
        sql.sql("UPDATE zonas SET estado='INACTIVO'").update();
        cotizar("[{\"sku\":123,\"cantidad\":1.5}]").andExpect(status().isOk())
                .andExpect(jsonPath("$.coberturaDisponible").value(false));
        assertThat(CONSULTAS).isEmpty(); assertSinFilas();
    }

    private void comprobarPersistenciaFallida() throws Exception {
        cotizar(linea("A")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_PERSISTENCIA_COTIZACION"))
                .andExpect(jsonPath("$.costoEnvio").doesNotExist());
        assertSinFilas();
    }

    private void assertSinFilas() { assertThat(sql.sql("SELECT count(*) FROM cotizaciones").query(Long.class).single()).isZero(); }
    private static String linea(String sku) { return "[{\"sku\":\"" + sku + "\",\"cantidad\":1}]"; }
    private ResultActions cotizar(String lineas) throws Exception {
        return http.perform(post("/api/v1/cotizaciones").header("Authorization", "Bearer canal")
                .contentType("application/json").content("{\"destino\":{\"distrito\":\"San Borja\"},\"lineas\":" + lineas + "}"));
    }

    private static String perfil(String sku, String estado) {
        return JSON.writeValueAsString(Map.of("sku", sku, "estado", estado, "actualizadoEn", "2026-11-05T12:00:00Z",
                "pesoKg", new BigDecimal("0.123456789123456789"), "dimensionesCm", Map.of("largo", 10, "ancho", 10, "alto", 11)));
    }

    private static HttpServer iniciar() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/api/v1/productos/datos-fisicos/consulta", solicitud -> {
                CREDENCIALES.add(solicitud.getRequestHeaders().getFirst("Authorization"));
                JsonNode cuerpo = JSON.readTree(solicitud.getRequestBody()); CONSULTAS.add(cuerpo);
                String respuesta = CUERPO.get();
                if (respuesta.equals("AUTO")) {
                    List<String> perfiles = new java.util.ArrayList<>();
                    cuerpo.path("skus").forEach(sku -> perfiles.add(perfil(sku.asText(), COMERCIAL.get())));
                    respuesta = "{\"productos\":[" + String.join(",", perfiles) + "],\"noEncontrados\":[]}";
                }
                byte[] bytes = respuesta.getBytes(StandardCharsets.UTF_8);
                solicitud.getResponseHeaders().set("Content-Type", ESTADO.get() == 200 ? "application/json" : "application/problem+json");
                solicitud.sendResponseHeaders(ESTADO.get(), bytes.length); solicitud.getResponseBody().write(bytes); solicitud.close();
            });
            servidor.start(); return servidor;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
