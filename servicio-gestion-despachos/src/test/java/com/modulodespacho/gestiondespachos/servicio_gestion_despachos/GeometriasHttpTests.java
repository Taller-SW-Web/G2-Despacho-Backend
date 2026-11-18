package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.ProveedorDatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.DatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RegistroAuditoria;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** HTTP y PostGIS desechable; identidades y Productos son dobles explícitos. */
@Import({TestcontainersConfiguration.class, GeometriasHttpTests.RelojPruebas.class})
@SpringBootTest(properties = {"SEGURIDAD_URL=http://localhost:9999", "spring.flyway.enabled=true"})
@AutoConfigureMockMvc
class GeometriasHttpTests {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class RelojPruebas {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        java.time.Clock relojGeografico() {
            return java.time.Clock.fixed(
                    Instant.parse("2026-11-14T15:00:00Z"), java.time.ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired JsonMapper json;
    @MockitoBean JwtDecoder jwt;

    @MockitoBean(name = "productosProgramacion")
    ProveedorDatosFisicos productos;

    @MockitoSpyBean RegistroAuditoria auditoria;
    private static final String RUTA = "/api/v1/distritos/geometrias";
    private static final String POLIGONO =
            "{\"type\":\"Polygon\",\"coordinates\":[[[-77.1,-12.1],[-77.0,-12.1],[-77.0,-12.0],[-77.1,-12.0],[-77.1,-12.1]]]}";
    private static final String MULTIPLE =
            """
            {"type":"MultiPolygon","coordinates":[
              [[[-77.1,-12.1],[-77.0,-12.1],[-77.0,-12.0],[-77.1,-12.0],[-77.1,-12.1]],
               [[-77.08,-12.08],[-77.08,-12.02],[-77.02,-12.02],[-77.02,-12.08],[-77.08,-12.08]]],
              [[[-76.99,-12.1],[-76.98,-12.1],[-76.98,-12.0],[-76.99,-12.0],[-76.99,-12.1]]]]}
            """;

    @BeforeEach
    void preparar() {
        sql.sql("TRUNCATE zonas CASCADE").update();
        sql.sql(
                        "TRUNCATE validaciones_geograficas,geometrias_distrito,historico_geometrias CASCADE")
                .update();
        sql.sql("TRUNCATE auditoria_configuracion,comandos_configuracion").update();
        sql.sql("UPDATE distrito SET geom=NULL,estado='ACTIVO',version=0").update();
        for (String identidad : List.of("gestor", "segundo", "otro", "tecnico")) {
            when(jwt.decode(identidad))
                    .thenReturn(
                            Jwt.withTokenValue(identidad)
                                    .header("alg", "RS256")
                                    .subject(
                                            identidad.equals("segundo")
                                                    ? "00000000-0000-4000-8000-000000000002"
                                                    : "00000000-0000-4000-8000-000000000001")
                                    .claim(
                                            "tipo",
                                            identidad.equals("tecnico") ? "servicio" : "acceso")
                                    .claim(
                                            "roles",
                                            identidad.equals("otro")
                                                    ? List.of("ADMIN_SISTEMA")
                                                    : List.of("GESTOR_DESPACHO"))
                                    .claim("scope", "cotizaciones:calcular")
                                    .issuedAt(Instant.now())
                                    .expiresAt(Instant.now().plusSeconds(3600))
                                    .build());
        }
        when(productos.obtener(any()))
                .thenReturn(
                        Map.of(
                                "SKU-1",
                                new DatosFisicos(
                                        new BigDecimal("1.25"),
                                        new BigDecimal("30"),
                                        new BigDecimal("20"),
                                        new BigDecimal("10"),
                                        "ACTIVO",
                                        Instant.parse("2026-11-01T12:00:00Z"))));
    }

    @Test
    void consultaFaltantesFiltrosYComposicionPostalSinModificarCatalogo() throws Exception {
        var capa =
                arbol(
                        enviar(get(RUTA))
                                .andExpect(status().isOk())
                                .andExpect(
                                        content()
                                                .contentTypeCompatibleWith(
                                                        "application/geo+json")));
        assertThat(capa.path("features").size()).isEqualTo(50);
        assertThat(capa.path("features").get(0).path("geometry").isNull()).isTrue();
        assertThat(capa.path("features").get(0).path("properties").path("versionGeometria").asInt())
                .isZero();
        assertThat(arbol(enviar(get("/api/v1/distritos"))).get(0).has("geometry")).isFalse();
        sql.sql("UPDATE distrito SET estado='INACTIVO' WHERE ubigeo='070107'").update();
        assertThat(
                        arbol(enviar(get(RUTA).param("estado", "INACTIVO").param("nombre", "Perú")))
                                .path("features")
                                .get(0)
                                .path("properties")
                                .path("ubigeo")
                                .asText())
                .isEqualTo("070107");
        String zona = crearZona(true);
        var composicion =
                arbol(
                        enviar(get("/api/v1/zonas/" + zona + "/geometria"))
                                .andExpect(status().isOk()));
        assertThat(composicion.path("features").size()).isEqualTo(1);
        assertThat(
                        composicion
                                .path("features")
                                .get(0)
                                .path("properties")
                                .path("soloCodigosPostales")
                                .asBoolean())
                .isTrue();
        enviar(get("/api/v1/zonas/" + UUID.randomUUID() + "/geometria"))
                .andExpect(status().isNotFound());
        enviar(get(RUTA).param("estado", "OTRO")).andExpect(status().isBadRequest());
        enviar(get(RUTA).param("nombre", "a", "b")).andExpect(status().isBadRequest());
    }

    @Test
    void validaSinMutarYAsociaMiPeruConCerosIniciales() throws Exception {
        var informe = validar("070107", MULTIPLE);
        assertThat(informe.path("valida").asBoolean()).isTrue();
        assertThat(informe.path("distritos").get(0).path("id").asText()).isEqualTo(id("070107"));
        assertThat(informe.path("distritos").get(0).path("accion").asText())
                .isEqualTo("PRIMERA_CARGA");
        assertThat(cantidad("SELECT count(*) FROM distrito WHERE geom IS NOT NULL")).isZero();
        assertThat(cantidad("SELECT count(*) FROM auditoria_configuracion")).isZero();
        assertThat(cantidad("SELECT count(*) FROM historico_geometrias")).isZero();
        assertThat(informe.path("propuesta").path("features").get(0).path("geometry"))
                .isEqualTo(json.readTree(MULTIPLE));
    }

    @Test
    void importaReemplazaConservaPartesHuecosHistoricoYRecuperaRespuestaExacta() throws Exception {
        String distrito = id("150130");
        String identidadAntes =
                sql.sql(
                                "SELECT (to_jsonb(d)-'geom'-'actualizado_en')::text FROM distrito d WHERE id=CAST(:id AS uuid)")
                        .param("id", distrito)
                        .query(String.class)
                        .single();
        ObjectNode archivo = solicitud("150130", MULTIPLE);
        archivo.put("nombreArchivo", " san_borja.geojson ");
        var validacion =
                arbol(
                        enviar(
                                        post(RUTA + "/validaciones")
                                                .content(json.writeValueAsString(archivo)))
                                .andExpect(status().isOk()));
        assertThat(validacion.path("nombreArchivo").asText()).isEqualTo("san_borja.geojson");
        String clave = UUID.randomUUID().toString();
        var primero = importar(validacion, clave).andExpect(status().isOk());
        JsonNode resultado = arbol(primero);
        assertThat(resultado.path("nombreArchivo").asText()).isEqualTo("san_borja.geojson");
        assertThat(
                        arbol(enviar(get(RUTA).param("nombre", "San Borja")))
                                .path("features")
                                .get(0)
                                .path("properties")
                                .path("nombreArchivo")
                                .asText())
                .isEqualTo("san_borja.geojson");
        assertThat(
                        cantidad(
                                "SELECT extensions.ST_NumGeometries(geom) FROM distrito WHERE ubigeo='150130'"))
                .isEqualTo(2);
        assertThat(
                        cantidad(
                                "SELECT extensions.ST_NumInteriorRings(extensions.ST_GeometryN(geom,1)) FROM distrito WHERE ubigeo='150130'"))
                .isEqualTo(1);
        assertThat(
                        cantidad(
                                "SELECT count(*) FROM auditoria_configuracion WHERE entidad='geometria_distrito'"))
                .isEqualTo(1);
        var segundo = validar("150130", POLIGONO);
        assertThat(segundo.path("distritos").get(0).path("accion").asText()).isEqualTo("REEMPLAZO");
        importar(segundo, UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(
                        cantidad(
                                "SELECT count(*) FROM historico_geometrias WHERE version=1 AND extensions.ST_NumGeometries(geom)=2"))
                .isEqualTo(1);
        assertThat(
                        cantidad(
                                "SELECT version FROM geometrias_distrito WHERE distrito_id=(SELECT id FROM distrito WHERE ubigeo='150130')"))
                .isEqualTo(2);
        assertThat(
                        sql.sql(
                                        "SELECT (to_jsonb(d)-'geom'-'actualizado_en')::text FROM distrito d WHERE id=CAST(:id AS uuid)")
                                .param("id", distrito)
                                .query(String.class)
                                .single())
                .isEqualTo(identidadAntes);
        assertThat(arbol(importar(validacion, clave).andExpect(status().isOk())))
                .isEqualTo(resultado);
        importar(segundo, clave)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_OPERACION_REUTILIZADA"));
        importar(validacion, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_VALIDACION_CONSUMIDA"));
        assertThat(cantidad("SELECT count(*) FROM historico_geometrias")).isEqualTo(1);
        assertThat(
                        cantidad(
                                "SELECT count(*) FROM historico_geometrias h JOIN importaciones_geograficas i ON i.id=CAST(h.metadatos->>'importacion_id' AS uuid) WHERE i.nombre_archivo='san_borja.geojson'"))
                .isEqualTo(1);
    }

    @Test
    void requiereCorrespondenciaExplicitaYNuncaReemplazaUbigeoDeclarado() throws Exception {
        ObjectNode solicitud = solicitud("070107", POLIGONO);
        ObjectNode propiedades =
                (ObjectNode) solicitud.path("geojson").path("features").get(0).path("properties");
        propiedades.remove("ubigeo");
        propiedades
                .put("nombre", "Mi Perú")
                .put("provincia", "Callao")
                .put("departamento", "Callao");
        var informe =
                arbol(
                        enviar(
                                post(RUTA + "/validaciones")
                                        .content(json.writeValueAsString(solicitud))));
        assertThat(informe.path("valida").asBoolean()).isFalse();
        assertThat(informe.path("sugerencias").get(0).path("ubigeo").asText()).isEqualTo("070107");
        solicitud.set("correspondencias", json.readTree("[{\"indice\":0,\"ubigeo\":\"070107\"}]"));
        assertThat(
                        arbol(
                                        enviar(
                                                post(RUTA + "/validaciones")
                                                        .content(
                                                                json.writeValueAsString(
                                                                        solicitud))))
                                .path("valida")
                                .asBoolean())
                .isTrue();
        propiedades.put("ubigeo", "070107");
        assertThat(
                        arbol(
                                        enviar(
                                                post(RUTA + "/validaciones")
                                                        .content(
                                                                json.writeValueAsString(
                                                                        solicitud))))
                                .path("valida")
                                .asBoolean())
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"type\":\"Point\",\"coordinates\":[-77,-12]}",
                "{\"type\":\"Polygon\",\"coordinates\":[]}",
                "{\"type\":\"Polygon\",\"coordinates\":[[[-77,-12],[-76.9,-12],[-77,-12.1],[-76.9,-12.1],[-77,-12]]]}",
                "{\"type\":\"Polygon\",\"coordinates\":[[[300000,8600000],[300100,8600000],[300100,8600100],[300000,8600000]]]}",
                "{\"type\":\"Polygon\",\"coordinates\":[[[-77,-12],[-76.9,-12],[-76.9,-12.1],[-77,-12.1]]]}",
                "{\"type\":\"Polygon\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:32718\"}},\"coordinates\":[[[-77,-12],[-76.9,-12],[-76.9,-12.1],[-77,-12]]]}"
            })
    void informaErroresSinRepararNiCrearPropuestaConfirmable(String geometria) throws Exception {
        var informe = validar("150130", geometria);
        assertThat(informe.path("valida").asBoolean()).isFalse();
        assertThat(informe.path("validacionId").isNull()).isTrue();
        assertThat(informe.path("errores").size()).isGreaterThan(0);
        assertThat(cantidad("SELECT count(*) FROM validaciones_geograficas")).isZero();
        assertThat(cantidad("SELECT count(*) FROM distrito WHERE geom IS NOT NULL")).isZero();
    }

    @Test
    void rechazaUbigeosDesconocidosNumericosYDuplicados() throws Exception {
        assertThat(validar("150199", POLIGONO).path("valida").asBoolean()).isFalse();
        ObjectNode solicitud = solicitud("070107", POLIGONO);
        ((ObjectNode) solicitud.path("geojson").path("features").get(0).path("properties"))
                .put("ubigeo", 70107);
        assertThat(
                        arbol(
                                        enviar(
                                                post(RUTA + "/validaciones")
                                                        .content(
                                                                json.writeValueAsString(
                                                                        solicitud))))
                                .path("valida")
                                .asBoolean())
                .isFalse();
        solicitud = solicitud("150130", POLIGONO);
        var entidades =
                (tools.jackson.databind.node.ArrayNode) solicitud.path("geojson").path("features");
        entidades.add(entidades.get(0).deepCopy());
        assertThat(
                        arbol(
                                        enviar(
                                                post(RUTA + "/validaciones")
                                                        .content(
                                                                json.writeValueAsString(
                                                                        solicitud))))
                                .path("valida")
                                .asBoolean())
                .isFalse();
    }

    @Test
    void controlaPropietarioExpiracionYConcurrenciaAntesDeMutar() throws Exception {
        var validacion = validar("150130", POLIGONO);
        http.perform(
                        post(RUTA + "/importaciones")
                                .header("Authorization", "Bearer segundo")
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "validacionId",
                                                        validacion.path("validacionId").asText()))))
                .andExpect(status().isNotFound());
        sql.sql("UPDATE validaciones_geograficas SET expira_en='2026-11-14T14:59:00Z'").update();
        importar(validacion, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_VALIDACION_EXPIRADA"));
        validacion = validar("150130", POLIGONO);
        sql.sql("UPDATE distrito SET version=version+1 WHERE ubigeo='150130'").update();
        importar(validacion, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_GEOMETRIA_DESACTUALIZADA"));
        var primera = validar("150130", POLIGONO);
        var obsoleta = validar("150130", MULTIPLE);
        importar(primera, UUID.randomUUID().toString()).andExpect(status().isOk());
        importar(obsoleta, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertThat(cantidad("SELECT count(*) FROM importaciones_geograficas")).isEqualTo(1);
    }

    @Test
    void revierteTodoElLoteSiFallaAuditoria() throws Exception {
        ObjectNode solicitud = solicitud("150130", POLIGONO);
        var entidades =
                (tools.jackson.databind.node.ArrayNode) solicitud.path("geojson").path("features");
        entidades.add(solicitud("070107", MULTIPLE).path("geojson").path("features").get(0));
        JsonNode informe =
                arbol(
                        enviar(
                                post(RUTA + "/validaciones")
                                        .content(json.writeValueAsString(solicitud))));
        doThrow(new IllegalStateException("Fallo sintético de auditoría"))
                .when(auditoria)
                .registrar(eq("geometria_distrito"), eq(id("070107")), any(), any(), any(), any());
        try {
            importar(informe, UUID.randomUUID().toString()).andExpect(status().is5xxServerError());
        } finally {
            reset(auditoria);
        }
        assertThat(cantidad("SELECT count(*) FROM distrito WHERE geom IS NOT NULL")).isZero();
        assertThat(cantidad("SELECT count(*) FROM geometrias_distrito")).isZero();
        assertThat(cantidad("SELECT count(*) FROM importaciones_geograficas")).isZero();
        assertThat(cantidad("SELECT count(*) FROM comandos_configuracion")).isZero();
        assertThat(cantidad("SELECT count(*) FROM auditoria_configuracion")).isZero();
    }

    @Test
    void cotizacionConservaResultadoSinGeometriaTrasImportarYReemplazar() throws Exception {
        String zona = crearZona(false);
        enviar(
                        put("/api/v1/zonas/" + zona + "/tarifa")
                                .content(
                                        "{\"tarifaBase\":10,\"pesoIncluidoKg\":3,\"recargoKgAdicional\":2,\"factorVolumetrico\":1.25,\"moneda\":\"PEN\",\"plazoEstimadoDiasHabiles\":2}"))
                .andExpect(status().isOk());
        JsonNode antes = cotizar();
        assertThat(antes.path("costoEnvio").isNumber()).isTrue();
        importar(validar("150130", POLIGONO), UUID.randomUUID().toString())
                .andExpect(status().isOk());
        assertThat(cotizar()).isEqualTo(antes);
        importar(validar("150130", MULTIPLE), UUID.randomUUID().toString())
                .andExpect(status().isOk());
        assertThat(cotizar()).isEqualTo(antes);
        assertThat(cotizar().has("geometry")).isFalse();
    }

    @Test
    void limitaCuerpoYRequiereGestorHumanoEnLasCuatroOperaciones() throws Exception {
        enviar(post(RUTA + "/validaciones").content(" ".repeat(5_242_881)))
                .andExpect(status().isPayloadTooLarge());
        for (String token : List.of("", "otro", "tecnico")) {
            for (var peticion :
                    List.of(
                            get(RUTA),
                            get("/api/v1/zonas/" + UUID.randomUUID() + "/geometria"),
                            post(RUTA + "/validaciones").content("{}"),
                            post(RUTA + "/importaciones").content("{}"))) {
                if (!token.isEmpty()) peticion.header("Authorization", "Bearer " + token);
                http.perform(peticion.contentType("application/json"))
                        .andExpect(status().is(token.isEmpty() ? 401 : 403));
            }
        }
    }

    @Test
    void serializaConfirmacionesConcurrentesYReintentosConLaMismaClave() throws Exception {
        var primera = validar("150130", POLIGONO);
        var segunda = validar("150130", MULTIPLE);
        try (var hilos = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var inicio = new java.util.concurrent.CountDownLatch(1);
            var a =
                    hilos.submit(
                            () -> {
                                inicio.await();
                                return importar(primera, UUID.randomUUID().toString())
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var b =
                    hilos.submit(
                            () -> {
                                inicio.await();
                                return importar(segunda, UUID.randomUUID().toString())
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            inicio.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
            assertThat(cantidad("SELECT count(*) FROM importaciones_geograficas")).isEqualTo(1);
            var siguiente = validar("150130", POLIGONO);
            String clave = UUID.randomUUID().toString();
            var c =
                    hilos.submit(
                            () -> arbol(importar(siguiente, clave).andExpect(status().isOk())));
            var d =
                    hilos.submit(
                            () -> arbol(importar(siguiente, clave).andExpect(status().isOk())));
            assertThat(c.get()).isEqualTo(d.get());
            assertThat(cantidad("SELECT count(*) FROM importaciones_geograficas")).isEqualTo(2);
            assertThat(cantidad("SELECT count(*) FROM historico_geometrias")).isEqualTo(1);
        }
    }

    @Test
    void limitaEntidadesYComplejidadAntesDePostgisYRechazaJsonSobrante() throws Exception {
        for (String nombre :
                List.of(
                        "../limites.geojson",
                        "C:\\limites.geojson",
                        "invalido\n.geojson",
                        "a".repeat(256))) {
            ObjectNode archivo = solicitud("150130", POLIGONO);
            archivo.put("nombreArchivo", nombre);
            enviar(post(RUTA + "/validaciones").content(json.writeValueAsString(archivo)))
                    .andExpect(status().isBadRequest());
        }
        ObjectNode solicitud = solicitud("150130", POLIGONO);
        var entidades =
                (tools.jackson.databind.node.ArrayNode) solicitud.path("geojson").path("features");
        JsonNode entidad = entidades.get(0);
        for (int i = 1; i < 101; i++) entidades.add(entidad.deepCopy());
        assertThat(
                        arbol(
                                        enviar(
                                                post(RUTA + "/validaciones")
                                                        .content(
                                                                json.writeValueAsString(
                                                                        solicitud))))
                                .path("valida")
                                .asBoolean())
                .isFalse();
        solicitud = solicitud("150130", POLIGONO);
        var anillos =
                (tools.jackson.databind.node.ArrayNode)
                        solicitud
                                .path("geojson")
                                .path("features")
                                .get(0)
                                .path("geometry")
                                .path("coordinates");
        var anillo = json.createArrayNode();
        for (int i = 0; i < 200_001; i++) anillo.add(json.readTree("[-77,-12]"));
        anillos.removeAll();
        anillos.add(anillo);
        JsonNode informe =
                arbol(
                        enviar(
                                post(RUTA + "/validaciones")
                                        .content(json.writeValueAsString(solicitud))));
        assertThat(informe.path("valida").asBoolean()).isFalse();
        assertThat(informe.path("errores").get(0).path("motivo").asText()).contains("200 000");
        enviar(
                        post(RUTA + "/validaciones")
                                .content(
                                        json.writeValueAsString(solicitud("150130", POLIGONO))
                                                + " {}"))
                .andExpect(status().isBadRequest());
        assertThat(cantidad("SELECT count(*) FROM validaciones_geograficas")).isZero();
    }

    private JsonNode cotizar() throws Exception {
        return arbol(
                http.perform(
                                post("/api/v1/cotizaciones")
                                        .header("Authorization", "Bearer tecnico")
                                        .contentType("application/json")
                                        .content(
                                                "{\"destino\":{\"distrito\":\"San Borja\"},\"lineas\":[{\"sku\":\"SKU-1\",\"cantidad\":2}]}"))
                        .andExpect(status().isOk()));
    }

    private String crearZona(boolean postal) throws Exception {
        return arbol(
                        enviar(
                                        post("/api/v1/zonas")
                                                .content(
                                                        "{\"nombre\":\"Zona geográfica\",\"estado\":\"ACTIVO\",\"distritos\":[{\"distrito\":\"San Borja\",\"codigosPostales\":[\"15036\"],\"soloCodigosPostales\":"
                                                                + postal
                                                                + "}]}"))
                                .andExpect(status().isCreated()))
                .path("id")
                .asText();
    }

    private ObjectNode solicitud(String ubigeo, String geometria) {
        return (ObjectNode)
                json.readTree(
                        "{\"fuente\":\"Prueba geográfica\",\"versionFuente\":\"revision-1\",\"geojson\":{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"ubigeo\":\""
                                + ubigeo
                                + "\",\"nombre\":\"No debe editar catálogo\"},\"geometry\":"
                                + geometria
                                + "}]}}");
    }

    private JsonNode validar(String ubigeo, String geometria) throws Exception {
        return arbol(
                enviar(
                                post(RUTA + "/validaciones")
                                        .content(
                                                json.writeValueAsString(
                                                        solicitud(ubigeo, geometria))))
                        .andExpect(status().isOk()));
    }

    private ResultActions importar(JsonNode validacion, String clave) throws Exception {
        return enviar(
                post(RUTA + "/importaciones")
                        .header("Idempotency-Key", clave)
                        .content(
                                json.writeValueAsString(
                                        Map.of(
                                                "validacionId",
                                                validacion.path("validacionId").asText()))));
    }

    private ResultActions enviar(MockHttpServletRequestBuilder solicitud) throws Exception {
        return http.perform(
                solicitud.header("Authorization", "Bearer gestor").contentType("application/json"));
    }

    private JsonNode arbol(ResultActions respuesta) throws Exception {
        return json.readTree(respuesta.andReturn().getResponse().getContentAsString());
    }

    private long cantidad(String consulta) {
        return sql.sql(consulta).query(Long.class).single();
    }

    private String id(String ubigeo) {
        return sql.sql("SELECT id::text FROM distrito WHERE ubigeo=:codigo")
                .param("codigo", ubigeo)
                .query(String.class)
                .single();
    }
}
