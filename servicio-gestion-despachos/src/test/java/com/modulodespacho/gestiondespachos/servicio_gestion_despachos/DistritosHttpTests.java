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
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API y PostgreSQL reales; identidad, Productos y reloj controlados, sin bases persistentes. */
@Import({TestcontainersConfiguration.class, DistritosHttpTests.RelojPruebas.class})
@SpringBootTest(properties = {"SEGURIDAD_URL=http://localhost:9999", "spring.flyway.enabled=true"})
@AutoConfigureMockMvc
class DistritosHttpTests {
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired JsonMapper json;
    @Autowired org.testcontainers.postgresql.PostgreSQLContainer postgres;
    @MockitoBean JwtDecoder jwt;

    @MockitoBean(name = "productosProgramacion")
    ProveedorDatosFisicos productos;

    @MockitoSpyBean RegistroAuditoria auditoria;
    static final String GESTOR = "00000000-0000-4000-8000-000000000001";

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojPruebas {
        @Bean
        @Primary
        Clock relojDistrito() {
            return Clock.fixed(Instant.parse("2026-11-13T15:00:00Z"), ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void preparar() {
        sql.sql("TRUNCATE zonas CASCADE").update();
        sql.sql("TRUNCATE auditoria_configuracion, comandos_configuracion, claves_idempotencia")
                .update();
        sql.sql("DELETE FROM distrito WHERE NOT catalogo_base").update();
        sql.sql("UPDATE distrito SET estado='ACTIVO', version=0").update();
        when(jwt.decode("gestor"))
                .thenReturn(token(GESTOR, "acceso", "", List.of("GESTOR_DESPACHO")));
        when(jwt.decode("otro")).thenReturn(token(GESTOR, "acceso", "", List.of("ADMIN_SISTEMA")));
        when(jwt.decode("tecnico"))
                .thenReturn(
                        token(
                                "modulo-chatbot",
                                "servicio",
                                "cotizaciones:calcular",
                                List.of("GESTOR_DESPACHO")));
        when(jwt.decode("ventas"))
                .thenReturn(token("modulo-ventas", "servicio", "despachos:crear", List.of()));
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
    void migraV10ConservandoIdentidadesDatosYAsociaciones() throws Exception {
        String base = "distritos_" + UUID.randomUUID().toString().replace("-", "");
        sql.sql("CREATE DATABASE " + base).update();
        String url =
                "jdbc:postgresql://"
                        + postgres.getHost()
                        + ":"
                        + postgres.getMappedPort(5432)
                        + "/"
                        + base;
        org.flywaydb.core.Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target("10")
                .load()
                .migrate();
        String original;
        try (var conexion =
                        java.sql.DriverManager.getConnection(
                                url, postgres.getUsername(), postgres.getPassword());
                var consulta = conexion.createStatement()) {
            consulta.execute(
                    "INSERT INTO distrito(nombre,provincia,departamento,ubigeo) VALUES ('Distrito previo','Lima','Lima','150199')");
            consulta.execute("INSERT INTO zonas(nombre,estado) VALUES ('Zona previa','ACTIVO')");
            consulta.execute(
                    "INSERT INTO zonas_distritos(zona_id,distrito_id) SELECT z.id,d.id FROM zonas z,distrito d WHERE d.nombre='San Borja'");
            consulta.execute(
                    "INSERT INTO zonas_codigos_postales(zona_id,distrito_id,codigo_postal) SELECT z.id,d.id,'15036' FROM zonas z,distrito d WHERE d.nombre='San Borja'");
            var fila =
                    consulta.executeQuery(
                            "SELECT jsonb_agg(to_jsonb(d) ORDER BY ubigeo)::text FROM distrito d");
            assertThat(fila.next()).isTrue();
            original = fila.getString(1);
        }
        org.flywaydb.core.Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        try (var conexion =
                        java.sql.DriverManager.getConnection(
                                url, postgres.getUsername(), postgres.getPassword());
                var consulta = conexion.createStatement()) {
            var datos =
                    consulta.executeQuery(
                            "SELECT jsonb_agg(to_jsonb(d)-'estado'-'version'-'catalogo_base' ORDER BY ubigeo)::text FROM distrito d");
            assertThat(datos.next()).isTrue();
            assertThat(datos.getString(1)).isEqualTo(original);
            var cantidades =
                    consulta.executeQuery(
                            "SELECT (SELECT count(*) FROM distrito WHERE catalogo_base),(SELECT count(*) FROM distrito WHERE estado='ACTIVO' AND version=0),(SELECT count(*) FROM zonas_distritos),(SELECT count(*) FROM zonas_codigos_postales)");
            assertThat(cantidades.next()).isTrue();
            assertThat(cantidades.getInt(1)).isEqualTo(50);
            assertThat(cantidades.getInt(2)).isEqualTo(51);
            assertThat(cantidades.getInt(3)).isEqualTo(1);
            assertThat(cantidades.getInt(4)).isEqualTo(1);
        }
    }

    @Test
    void migracionConservaCatalogoBaseYFiltrosCompatibles() throws Exception {
        assertThat(
                        sql.sql(
                                        "SELECT count(*) FROM distrito WHERE catalogo_base AND estado='ACTIVO' AND version=0")
                                .query(Long.class)
                                .single())
                .isEqualTo(50);
        var lista = leer(gestor(get("/api/v1/distritos"))).andExpect(status().isOk()).andReturn();
        assertThat(arbol(lista).size()).isEqualTo(50);
        String id = id("San Borja");
        estado(id, "INACTIVO", 0).andExpect(status().isOk());
        assertThat(arbol(leer(gestor(get("/api/v1/distritos"))).andReturn()).size()).isEqualTo(49);
        leer(gestor(get("/api/v1/distritos").param("estado", "INACTIVO").param("nombre", "borJA")))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].catalogoBase").value(true))
                .andExpect(jsonPath("$[0].identidadEditable").value(false));
        assertThat(
                        arbol(
                                        leer(gestor(
                                                        get("/api/v1/distritos")
                                                                .param("estado", "TODOS")))
                                                .andReturn())
                                .size())
                .isEqualTo(50);
        leer(gestor(get("/api/v1/distritos/" + UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void altaEdicionBajaReactivacionYAuditoriaSinCambiosDuplicados() throws Exception {
        String id = crear("  Distrito de prueba  ", "070199");
        leer(gestor(get("/api/v1/distritos/" + id)))
                .andExpect(jsonPath("$.nombre").value("Distrito de prueba"))
                .andExpect(jsonPath("$.ubigeo").value("070199"))
                .andExpect(jsonPath("$.identidadEditable").value(true));
        String datos = datos("Distrito actualizado", "070198", 0L);
        leer(gestor(put("/api/v1/distritos/" + id)).content(datos))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionDistrito").value(1));
        leer(gestor(put("/api/v1/distritos/" + id))
                        .content(datos("Distrito actualizado", "070198", 1L)))
                .andExpect(status().isOk());
        assertThat(auditorias(id)).isEqualTo(2);
        var baja =
                leer(gestor(delete("/api/v1/distritos/" + id).param("versionDistrito", "1")))
                        .andExpect(status().isOk())
                        .andReturn();
        var repetida = estado(id, "INACTIVO", 0).andExpect(status().isOk()).andReturn();
        assertThat(arbol(repetida)).isEqualTo(arbol(baja));
        assertThat(auditorias(id)).isEqualTo(3);
        estado(id, "ACTIVO", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.versionDistrito").value(2));
        estado(id, "ACTIVO", 2).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertThat(auditorias(id)).isEqualTo(4);
        assertThat(
                        sql.sql(
                                        "SELECT DISTINCT usuario_id FROM auditoria_configuracion WHERE entidad_id=:id")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .isEqualTo(GESTOR);
    }

    @Test
    void protegeCatalogoBaseEIdentidadAsociadaInclusoPostalYZonaInactiva() throws Exception {
        leer(gestor(put("/api/v1/distritos/" + id("San Borja")))
                        .content(datos("Otro nombre", "070199", 0L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_IDENTIDAD_DISTRITO_PROTEGIDA"));
        String nuevo = crear("Distrito asociado", "070199");
        zona(
                "Postal inactiva",
                "[{\"distrito\":\"Distrito asociado\",\"codigosPostales\":[\"07000\"],\"soloCodigosPostales\":true}]",
                "INACTIVO");
        leer(gestor(get("/api/v1/distritos/" + nuevo)))
                .andExpect(jsonPath("$.identidadEditable").value(false));
        leer(gestor(put("/api/v1/distritos/" + nuevo)).content(datos("Renombrado", "070199", 0L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_IDENTIDAD_DISTRITO_PROTEGIDA"));
        assertThat(auditorias(nuevo)).isEqualTo(1);
    }

    @Test
    void unicidadIncluyeInactivosYNormalizaMayusculas() throws Exception {
        String id = crear("Prueba única", "070199");
        estado(id, "INACTIVO", 0).andExpect(status().isOk());
        leer(gestor(post("/api/v1/distritos")).content(datos("  PRUEBA ÚNICA ", "070198", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errores[0].campo").value("nombre"));
        leer(gestor(post("/api/v1/distritos")).content(datos("Nombre diferente", "070199", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errores[0].campo").value("ubigeo"));
        assertThat(auditorias(id)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"nombre\":\" \"}",
                "{\"nombre\":\"Nuevo\",\"provincia\":\"Lima\",\"departamento\":\"Callao\",\"ubigeo\":\"070199\"}",
                "{\"nombre\":\"Nuevo\",\"provincia\":\"Callao\",\"departamento\":\"Callao\",\"ubigeo\":70199}",
                "{\"nombre\":\"Nuevo\",\"provincia\":\"Callao\",\"departamento\":\"Callao\",\"ubigeo\":\"150199\"}",
                "{\"nombre\":\"Nuevo\",\"provincia\":\"Callao\",\"departamento\":\"Callao\",\"ubigeo\":\"070100\"}",
                "{\"nombre\":\"Nuevo\",\"provincia\":\"Callao\",\"departamento\":\"Callao\",\"ubigeo\":\"070199\",\"estado\":\"INACTIVO\"}"
            })
    void validaTiposGeografiaYCamposEscribibles(String cuerpo) throws Exception {
        leer(gestor(post("/api/v1/distritos")).content(cuerpo))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_DISTRITO_INVALIDOS"));
        assertThat(sql.sql("SELECT count(*) FROM distrito").query(Long.class).single())
                .isEqualTo(50);
    }

    @Test
    void rechazaVersionesFiltrosYParametrosInvalidos() throws Exception {
        String id = id("San Borja");
        for (String version : List.of("null", "-1", "1.5", "\"0\"")) {
            leer(gestor(patch("/api/v1/distritos/" + id + "/estado"))
                            .content(
                                    "{\"estado\":\"INACTIVO\",\"versionDistrito\":"
                                            + version
                                            + "}"))
                    .andExpect(status().isBadRequest());
        }
        leer(gestor(delete("/api/v1/distritos/" + id))).andExpect(status().isBadRequest());
        leer(gestor(get("/api/v1/distritos").param("estado", "NO_EXISTE")))
                .andExpect(status().isBadRequest());
        leer(gestor(get("/api/v1/distritos").param("estado", "ACTIVO", "INACTIVO")))
                .andExpect(status().isBadRequest());
        leer(gestor(get("/api/v1/distritos").param("otro", "x")))
                .andExpect(status().isBadRequest());
        assertThat(auditorias(id)).isZero();
    }

    @Test
    void todasLasOperacionesRequierenGestorHumano() throws Exception {
        String id = id("San Borja");
        for (String token : List.of("", "otro", "tecnico")) {
            var operaciones =
                    List.of(
                            get("/api/v1/distritos"),
                            get("/api/v1/distritos/" + id),
                            post("/api/v1/distritos"),
                            put("/api/v1/distritos/" + id),
                            delete("/api/v1/distritos/" + id),
                            patch("/api/v1/distritos/" + id + "/estado"));
            for (var operacion : operaciones) {
                if (!token.isEmpty()) operacion.header("Authorization", "Bearer " + token);
                leer(operacion.content("{}")).andExpect(status().is(token.isEmpty() ? 401 : 403));
            }
        }
    }

    @Test
    void falloAuditoriaRevierteAltaYEstado() throws Exception {
        doThrow(new IllegalStateException("Fallo controlado de auditoría"))
                .when(auditoria)
                .registrar(any(), any(), any(), any(), any(), any());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> crear("Rollback", "070199"))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(
                            sql.sql("SELECT count(*) FROM distrito WHERE nombre='Rollback'")
                                    .query(Long.class)
                                    .single())
                    .isZero();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> estado(id("San Borja"), "INACTIVO", 0))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(
                            sql.sql("SELECT estado FROM distrito WHERE nombre='San Borja'")
                                    .query(String.class)
                                    .single())
                    .isEqualTo("ACTIVO");
        } finally {
            reset(auditoria);
        }
    }

    @Test
    void dosEdicionesConcurrentesNoSeSobrescribenYAltaDuplicadaSeSerializa() throws Exception {
        String id = crear("Concurrente", "070199");
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            var uno =
                    hilos.submit(
                            () ->
                                    leer(gestor(put("/api/v1/distritos/" + id))
                                                    .content(datos("Primero", "070199", 0L)))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            var dos =
                    hilos.submit(
                            () ->
                                    leer(gestor(put("/api/v1/distritos/" + id))
                                                    .content(datos("Segundo", "070199", 0L)))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            assertThat(List.of(uno.get(), dos.get())).containsExactlyInAnyOrder(200, 409);
            assertThat(auditorias(id)).isEqualTo(2);
            var altaUno =
                    hilos.submit(
                            () ->
                                    leer(gestor(post("/api/v1/distritos"))
                                                    .content(
                                                            datos(
                                                                    "Duplicado concurrente",
                                                                    "070198",
                                                                    null)))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            var altaDos =
                    hilos.submit(
                            () ->
                                    leer(gestor(post("/api/v1/distritos"))
                                                    .content(
                                                            datos(
                                                                    "Duplicado concurrente",
                                                                    "070198",
                                                                    null)))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            assertThat(List.of(altaUno.get(), altaDos.get())).containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void bajaBloqueaRespaldoPostalConservaHistoricoYOtrosDistritosYRecuperaCalculo()
            throws Exception {
        String zona =
                zona(
                        "Mixta",
                        "[{\"distrito\":\"San Borja\",\"codigosPostales\":[\"15036\"]},{\"distrito\":\"Miraflores\",\"codigosPostales\":[\"15074\"]}]",
                        "ACTIVO");
        tarifa(zona);
        JsonNode antes =
                arbol(
                        cotizar("San Borja", "15036", true)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.tipoCotizacion").value("EXACTA"))
                                .andReturn());
        String historico =
                sql.sql("SELECT row_to_json(c)::text FROM cotizaciones c")
                        .query(String.class)
                        .single();
        estado(id("San Borja"), "INACTIVO", 0).andExpect(status().isOk());
        clearInvocations(productos);
        for (String postal : List.of("", "15036", "15074")) {
            cotizar("san borja", postal, true)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.coberturaDisponible").value(false))
                    .andExpect(jsonPath("$.costoEnvio").doesNotExist())
                    .andExpect(jsonPath("$.plazoEstimadoDiasHabiles").doesNotExist());
        }
        verifyNoInteractions(productos);
        assertThat(
                        sql.sql("SELECT row_to_json(c)::text FROM cotizaciones c")
                                .query(String.class)
                                .single())
                .isEqualTo(historico);
        cotizar("Miraflores", "15074", false)
                .andExpect(jsonPath("$.coberturaDisponible").value(true));
        estado(id("San Borja"), "ACTIVO", 1).andExpect(status().isOk());
        JsonNode despues =
                arbol(cotizar("San Borja", "15036", true).andExpect(status().isOk()).andReturn());
        assertThat(despues).isEqualTo(antes);
    }

    @Test
    void coberturaPostalExclusivaZonaInactivaYSinTarifa() throws Exception {
        String zona =
                zona(
                        "Solo postal",
                        "[{\"distrito\":\"San Borja\",\"codigosPostales\":[\"15036\"],\"soloCodigosPostales\":true}]",
                        "ACTIVO");
        cotizar("San Borja", "15036", true)
                .andExpect(jsonPath("$.coberturaDisponible").value(true))
                .andExpect(jsonPath("$.costoEnvio").doesNotExist());
        verifyNoInteractions(productos);
        estado(id("San Borja"), "INACTIVO", 0).andExpect(status().isOk());
        cotizar("San Borja", "15036", false)
                .andExpect(jsonPath("$.coberturaDisponible").value(false));
        cotizar("Desconocido", "15036", false)
                .andExpect(jsonPath("$.coberturaDisponible").value(false));
        leer(gestor(patch("/api/v1/zonas/" + zona + "/estado"))
                        .content("{\"estado\":\"INACTIVO\"}"))
                .andExpect(status().isOk());
        estado(id("San Borja"), "ACTIVO", 1).andExpect(status().isOk());
        cotizar("San Borja", "15036", false)
                .andExpect(jsonPath("$.coberturaDisponible").value(false));
        crear("Sin zona", "070199");
        cotizar("Sin zona", "", false).andExpect(jsonPath("$.coberturaDisponible").value(false));
    }

    @Test
    void conservaORetiraAsociacionesInactivasSinAmpliarlasNiLiberarExclusividad() throws Exception {
        String zona =
                zona(
                        "Conservar",
                        "[{\"distrito\":\"San Borja\",\"codigosPostales\":[\"15036\"],\"soloCodigosPostales\":true},{\"distrito\":\"Miraflores\"}]",
                        "ACTIVO");
        estado(id("San Borja"), "INACTIVO", 0).andExpect(status().isOk());
        String preservada =
                "{\"nombre\":\"Renombrada\",\"distritos\":[{\"distrito\":\"San Borja\",\"codigosPostales\":[\"15036\"],\"soloCodigosPostales\":true},{\"distrito\":\"Miraflores\"}]}";
        leer(gestor(put("/api/v1/zonas/" + zona)).content(preservada)).andExpect(status().isOk());
        leer(gestor(get("/api/v1/zonas/" + zona)))
                .andExpect(jsonPath("$.distritos[1].estado").value("INACTIVO"));
        leer(gestor(put("/api/v1/zonas/" + zona))
                        .content(
                                preservada.replace(
                                        "\"soloCodigosPostales\":true",
                                        "\"soloCodigosPostales\":false")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DISTRITO_INACTIVO"));
        leer(gestor(post("/api/v1/zonas"))
                        .content(
                                "{\"nombre\":\"Nueva\",\"distritos\":[{\"distrito\":\"San Borja\"}]}"))
                .andExpect(status().isBadRequest());
        leer(gestor(put("/api/v1/zonas/" + zona))
                        .content(
                                "{\"nombre\":\"Retirada\",\"distritos\":[{\"distrito\":\"Miraflores\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distritos.length()").value(1));
    }

    @Test
    void recepcionNuevaRechazaBajaPeroRecuperaCreacionAceptadaAntesYSimulacionEligeActivos()
            throws Exception {
        String zona =
                zona(
                        "Despachos",
                        "[{\"distrito\":\"San Borja\"},{\"distrito\":\"Miraflores\"}]",
                        "ACTIVO");
        tarifa(zona);
        String clave = UUID.randomUUID().toString();
        String pedido =
                "{\"idPedido\":\"PED-DISTRITO\",\"destinatario\":{\"nombre\":\"Persona de prueba\",\"telefono\":\"+51999999999\"},\"destino\":{\"direccion\":\"Dirección de prueba\",\"distrito\":\"San Borja\",\"provincia\":\"Lima\",\"departamento\":\"Lima\"},\"lineas\":[{\"sku\":\"SKU-1\",\"cantidad\":1}]}";
        var original =
                leer(post("/api/v1/despachos")
                                .header("Authorization", "Bearer ventas")
                                .header("Idempotency-Key", clave)
                                .content(pedido))
                        .andExpect(status().isCreated())
                        .andReturn();
        estado(id("San Borja"), "INACTIVO", 0).andExpect(status().isOk());
        clearInvocations(productos);
        leer(post("/api/v1/despachos")
                        .header("Authorization", "Bearer ventas")
                        .header("Idempotency-Key", clave)
                        .content(pedido))
                .andExpect(status().isCreated())
                .andExpect(content().json(original.getResponse().getContentAsString()));
        leer(post("/api/v1/despachos")
                        .header("Authorization", "Bearer ventas")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .content(pedido.replace("PED-DISTRITO", "PED-NUEVO")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_SIN_COBERTURA"));
        verifyNoInteractions(productos);
        for (int intento = 0; intento < 4; intento++)
            leer(gestor(post("/api/v1/despachos/simulaciones"))
                            .content(
                                    "{\"idZona\":\""
                                            + zona
                                            + "\",\"pesoKg\":1,\"volumenM3\":0.001}"))
                    .andExpect(status().isCreated());
        assertThat(
                        sql.sql("SELECT DISTINCT distrito FROM despachos WHERE es_simulado")
                                .query(String.class)
                                .list())
                .containsExactly("Miraflores");
        estado(id("Miraflores"), "INACTIVO", 0).andExpect(status().isOk());
        leer(gestor(post("/api/v1/despachos/simulaciones"))
                        .content("{\"idZona\":\"" + zona + "\",\"pesoKg\":1,\"volumenM3\":0.001}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private String crear(String nombre, String ubigeo) throws Exception {
        return arbol(
                        leer(gestor(post("/api/v1/distritos")).content(datos(nombre, ubigeo, null)))
                                .andExpect(status().isCreated())
                                .andExpect(header().exists("Location"))
                                .andReturn())
                .path("id")
                .asText();
    }

    private String datos(String nombre, String ubigeo, Long version) {
        return "{\"nombre\":\""
                + nombre
                + "\",\"provincia\":\"Callao\",\"departamento\":\"Callao\",\"ubigeo\":\""
                + ubigeo
                + "\""
                + (version == null ? "" : ",\"versionDistrito\":" + version)
                + "}";
    }

    private String zona(String nombre, String distritos, String estado) throws Exception {
        return arbol(
                        leer(gestor(post("/api/v1/zonas"))
                                        .content(
                                                "{\"nombre\":\""
                                                        + nombre
                                                        + "\",\"estado\":\""
                                                        + estado
                                                        + "\",\"distritos\":"
                                                        + distritos
                                                        + "}"))
                                .andExpect(status().isCreated())
                                .andReturn())
                .path("id")
                .asText();
    }

    private void tarifa(String zona) throws Exception {
        leer(gestor(put("/api/v1/zonas/" + zona + "/tarifa"))
                        .content(
                                "{\"tarifaBase\":10,\"pesoIncluidoKg\":3,\"recargoKgAdicional\":2,\"factorVolumetrico\":1.25,\"moneda\":\"PEN\",\"plazoEstimadoDiasHabiles\":2}"))
                .andExpect(status().isOk());
    }

    private ResultActions cotizar(String distrito, String postal, boolean lineas) throws Exception {
        return leer(
                post("/api/v1/cotizaciones")
                        .header("Authorization", "Bearer tecnico")
                        .content(
                                "{\"destino\":{\"distrito\":\""
                                        + distrito
                                        + "\",\"codigoPostal\":\""
                                        + postal
                                        + "\"}"
                                        + (lineas
                                                ? ",\"lineas\":[{\"sku\":\"SKU-1\",\"cantidad\":2}]"
                                                : "")
                                        + "}"));
    }

    private ResultActions estado(String id, String estado, long version) throws Exception {
        return leer(
                gestor(patch("/api/v1/distritos/" + id + "/estado"))
                        .content(
                                "{\"estado\":\""
                                        + estado
                                        + "\",\"versionDistrito\":"
                                        + version
                                        + "}"));
    }

    private String id(String nombre) {
        return sql.sql("SELECT id::text FROM distrito WHERE nombre=:nombre")
                .param("nombre", nombre)
                .query(String.class)
                .single();
    }

    private long auditorias(String id) {
        return sql.sql(
                        "SELECT count(*) FROM auditoria_configuracion WHERE entidad='distrito' AND entidad_id=:id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private MockHttpServletRequestBuilder gestor(MockHttpServletRequestBuilder solicitud) {
        return solicitud.header("Authorization", "Bearer gestor");
    }

    private ResultActions leer(MockHttpServletRequestBuilder solicitud) throws Exception {
        return http.perform(solicitud.contentType("application/json"));
    }

    private JsonNode arbol(MvcResult resultado) throws Exception {
        return json.readTree(resultado.getResponse().getContentAsString());
    }

    private Jwt token(String sub, String tipo, String scope, List<String> roles) {
        return Jwt.withTokenValue(sub)
                .header("alg", "RS256")
                .subject(sub)
                .claim("tipo", tipo)
                .claim("scope", scope)
                .claim("roles", roles)
                .audience(List.of("api-despacho"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }
}
