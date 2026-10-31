package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.RSAKey;
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
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.AsignarDespacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure.EntregaEventosProgramacion;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL, Flyway, JPA, seguridad RS256 y HTTP reales; servicios externos identificados como dobles. */
@Import({TestcontainersConfiguration.class, ProgramacionHttpTests.RelojPruebas.class})
@SpringBootTest(properties = "despacho.programacion.recuperacion-habilitada=false")
@AutoConfigureMockMvc
class ProgramacionHttpTests {
    static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    static final RSAKey CLAVE = clave();
    static final RSAKey OTRA_CLAVE = clave();
    static final HttpServer SERVICIOS = servidor();
    static final String URL = "http://127.0.0.1:" + SERVICIOS.getAddress().getPort();
    static final AtomicReference<String> MODO_PRODUCTOS = new AtomicReference<>("OK");
    static final AtomicReference<String> MODO_CAPACIDAD = new AtomicReference<>("OK");
    static final AtomicBoolean FALLAR_LIBERACION = new AtomicBoolean();
    static final AtomicBoolean FALLAR_VENTAS = new AtomicBoolean();
    static final AtomicBoolean REDIRIGIR_VENTAS = new AtomicBoolean();
    static final AtomicReference<Runnable> AL_RESERVAR = new AtomicReference<>(() -> {});
    static final List<JsonNode> CONSULTAS_PRODUCTOS = new CopyOnWriteArrayList<>();
    static final List<String> CLAVES_RESERVA = new CopyOnWriteArrayList<>();
    static final List<JsonNode> LLAMADAS_RESERVA = new CopyOnWriteArrayList<>();
    static final Map<String, String> RESERVAS = new ConcurrentHashMap<>();
    static final Set<String> LIBERADAS = ConcurrentHashMap.newKeySet();
    static final List<JsonNode> EVENTOS_VENTAS = new CopyOnWriteArrayList<>();
    static final List<JsonNode> PROYECCIONES = new CopyOnWriteArrayList<>();
    static final UUID ZONA = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID DISTRITO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired AsignarDespacho asignacion;
    @Autowired EntregaEventosProgramacion eventos;

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojPruebas {
        @Bean @Primary Clock relojF02() { return Clock.fixed(Instant.parse("2026-10-17T02:00:00Z"), ZoneOffset.UTC); }
    }
    @DynamicPropertySource static void configurar(DynamicPropertyRegistry propiedades) {
        propiedades.add("despacho.seguridad.url", () -> URL);
        propiedades.add("despacho.productos.url", () -> URL);
        propiedades.add("despacho.operacion.url", () -> URL);
        propiedades.add("despacho.ventas.url", () -> URL);
        propiedades.add("despacho.productos.token-servicio", () -> "tecnico-productos");
        propiedades.add("despacho.operacion.token-servicio", () -> "tecnico-operacion");
        propiedades.add("despacho.ventas.token-servicio", () -> "tecnico-ventas");
    }
    @BeforeEach void preparar() {
        sql.sql("DROP TRIGGER IF EXISTS prueba_fallo_historial ON historial_estados_despacho").update();
        sql.sql("TRUNCATE despachos, zonas, distrito, claves_idempotencia CASCADE").update();
        sql.sql("INSERT INTO distrito(id, nombre) VALUES (:id, 'San Borja')").param("id", DISTRITO).update();
        sql.sql("INSERT INTO zonas(id, nombre, estado) VALUES (:id, 'Lima Centro', 'ACTIVO')").param("id", ZONA).update();
        sql.sql("INSERT INTO zonas_distritos(zona_id, distrito_id) VALUES (:zona, :distrito)").param("zona", ZONA).param("distrito", DISTRITO).update();
        sql.sql("INSERT INTO tarifas_zona(zona_id, tarifa_base, plazo_max_dias) VALUES (:zona, 10, 2)").param("zona", ZONA).update();
        MODO_PRODUCTOS.set("OK"); MODO_CAPACIDAD.set("OK"); FALLAR_LIBERACION.set(false); FALLAR_VENTAS.set(false);
        REDIRIGIR_VENTAS.set(false);
        AL_RESERVAR.set(() -> {}); CONSULTAS_PRODUCTOS.clear(); CLAVES_RESERVA.clear(); LLAMADAS_RESERVA.clear();
        RESERVAS.clear(); LIBERADAS.clear(); EVENTOS_VENTAS.clear(); PROYECCIONES.clear();
    }
    @AfterAll static void detener() { SERVICIOS.stop(0); }

    @Test void recepcionCalculaEnLoteUnPaqueteFechaCivilHistorialYCodigosUnicos() throws Exception {
        JsonNode respuesta = recibir("PED-REAL-1", UUID.randomUUID().toString()).andExpect(status().isCreated())
                .andExpect(header().exists("Location")).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.estado").value("PENDIENTE_ASIGNACION"))
                .andExpect(jsonPath("$.pesoTotalKg").value(2.65)).andExpect(jsonPath("$.volumenTotalM3").value(0.02126))
                .andExpect(jsonPath("$.cantidadPaquetes").value(1)).andExpect(jsonPath("$.fechaProgramada").value("2026-10-20"))
                .andExpect(jsonPath("$.esSimulacion").value(false)).andExpect(jsonPath("$.codigoOperativo", startsWith("DSP-")))
                .andExpect(jsonPath("$.codigoRastreoInterno", startsWith("TRK-"))).andReturn().getResponse().getContentAsString().transform(JSON::readTree);
        assertThat(CONSULTAS_PRODUCTOS).hasSize(1);
        assertThat(CONSULTAS_PRODUCTOS.getFirst().path("skus").size()).isEqualTo(2);
        assertThat(contar("despachos")).isEqualTo(1);
        assertThat(contar("historial_estados_despacho")).isEqualTo(1);
        assertThat(contar("eventos_salientes")).isZero();
        assertThat(sql.sql("SELECT ejecutor_id FROM historial_estados_despacho").query(String.class).single()).isEqualTo("modulo-ventas");
        assertThat(sql.sql("SELECT pedido_id FROM despachos").query(String.class).single()).isEqualTo("PED-REAL-1");
        assertThat(respuesta.path("idDespacho").asText()).isNotBlank();
    }

    @Test void idempotenciaConservaRespuestaOriginalYNuevaClaveDevuelveEstadoActualSinReconsultarProductos() throws Exception {
        String clave = UUID.randomUUID().toString();
        String original = recibir("PED-IDEM", clave).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sql.sql("UPDATE despachos SET estado = 'CANCELADO', version = version + 1").update();
        recibir("PED-IDEM", clave).andExpect(status().isCreated()).andExpect(content().json(original));
        recibir("PED-IDEM", UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CANCELADO"));
        recibir("OTRO-PEDIDO", clave).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_OPERACION_REUTILIZADA"));
        assertThat(CONSULTAS_PRODUCTOS).hasSize(1);
        assertThat(contar("despachos")).isEqualTo(1);
        assertThat(contar("historial_estados_despacho")).isEqualTo(1);
    }

    @Test void dosCreacionesConcurrentesDelMismoPedidoNoDuplicanNiHistorialNiIdentidad() throws Exception {
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            var primera = hilos.submit(() -> recibir("PED-CONCURRENTE", UUID.randomUUID().toString()).andReturn().getResponse());
            var segunda = hilos.submit(() -> recibir("PED-CONCURRENTE", UUID.randomUUID().toString()).andReturn().getResponse());
            var uno = primera.get(10, TimeUnit.SECONDS); var dos = segunda.get(10, TimeUnit.SECONDS);
            assertThat(List.of(uno.getStatus(), dos.getStatus())).containsExactlyInAnyOrder(201, 200);
            assertThat(JSON.readTree(uno.getContentAsString()).path("idDespacho")).isEqualTo(JSON.readTree(dos.getContentAsString()).path("idDespacho"));
        }
        assertThat(contar("despachos")).isEqualTo(1); assertThat(contar("historial_estados_despacho")).isEqualTo(1);
    }

    @Test void rechazaSinCoberturaSinInvocarProductosNiDejarDatosParciales() throws Exception {
        sql.sql("UPDATE zonas SET estado = 'INACTIVO'").update();
        recibir("PED-SIN-COBERTURA", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_SIN_COBERTURA"));
        assertThat(CONSULTAS_PRODUCTOS).isEmpty(); assertThat(contar("despachos")).isZero();
    }

    @Test void rechazaSkuDesconocidoODatosFisicosInvalidosYPermiteReintentarUnaFallaSinGuardarClave() throws Exception {
        String clave = UUID.randomUUID().toString();
        for (String modo : List.of("DESCONOCIDO", "INCOMPLETO", "CAIDA", "LENTO")) {
            MODO_PRODUCTOS.set(modo);
            recibir("PED-PRODUCTOS", clave).andExpect(status().is(modo.equals("CAIDA") || modo.equals("LENTO") ? 503 : 422))
                    .andExpect(jsonPath("$.code").value(switch (modo) {
                        case "DESCONOCIDO" -> "DESP_ERROR_PRODUCTO_NO_ENCONTRADO";
                        case "INCOMPLETO" -> "DESP_ERROR_DATOS_FISICOS_INCOMPLETOS";
                        default -> "DESP_ERROR_PRODUCTOS_NO_DISPONIBLE";
                    }));
            assertThat(contar("despachos")).isZero(); assertThat(contar("claves_idempotencia")).isZero();
        }
        MODO_PRODUCTOS.set("OK"); recibir("PED-PRODUCTOS", clave).andExpect(status().isCreated());
    }

    @Test void validaTiposCamposObligatoriosExtrasYClaveAntesDeLlamarDependencias() throws Exception {
        String base = solicitud("PED-INVALIDO");
        List<String> invalidas = List.of("{}", base.replace("\"San Borja\"", "null"), base.replace("\"lineas\":[", "\"lineas\":[],\"extra\":["),
                base.replace("\"cantidad\":3", "\"cantidad\":0"), base.replace("\"cantidad\":3", "\"cantidad\":1.5"),
                base.replace("\"cantidad\":3", "\"cantidad\":\"3\""), base.replace("\"POL-NEG-M\"", "\" \""),
                base.substring(0, base.length() - 1) + ",\"pesoTotalKg\":2}");
        for (String cuerpo : invalidas) http.perform(post("/api/v1/despachos").header("Authorization", "Bearer " + ventas())
                        .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(cuerpo))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errores").isNotEmpty());
        for (String clave : List.of("", "no-uuid", "00000000-0000-1000-8000-000000000000"))
            recibir("PED-CLAVE", clave).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errores[0].campo").value("Idempotency-Key"));
        http.perform(post("/api/v1/despachos").header("Authorization", "Bearer " + ventas()).contentType("application/json").content(base)).andExpect(status().isBadRequest());
        assertThat(CONSULTAS_PRODUCTOS).isEmpty(); assertThat(contar("despachos")).isZero();
    }

    @Test void aplicaSeguridadRs256EmisorVencimientoAudienciaTipoSubScopeYRolAntesDelNegocio() throws Exception {
        List<String> noAceptados = List.of("invalido", jwt("servicio", "modulo-ventas", "despachos:crear", List.of(), "otra-api", 300, CLAVE, URL + "/api/v1/auth"),
                jwt("servicio", "modulo-ventas", "despachos:crear", List.of(), "api-despacho", -120, CLAVE, URL + "/api/v1/auth"),
                jwt("refresco", "usuario", "despachos:crear", List.of(), "api-despacho", 300, CLAVE, URL + "/api/v1/auth"),
                jwt("servicio", "modulo-ventas", "despachos:crear", List.of(), "api-despacho", 300, OTRA_CLAVE, URL + "/api/v1/auth"),
                jwt("servicio", "modulo-ventas", "despachos:crear", List.of(), "api-despacho", 300, CLAVE, "otro-emisor"));
        http.perform(post("/api/v1/despachos").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
        for (String token : noAceptados) http.perform(post("/api/v1/despachos").header("Authorization", "Bearer " + token).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
        for (String token : List.of(jwt("servicio", "modulo-ventas", "seguimientos:leer", List.of(), "api-despacho", 300, CLAVE, URL + "/api/v1/auth"),
                jwt("servicio", "modulo-chatbot", "despachos:crear", List.of(), "api-despacho", 300, CLAVE, URL + "/api/v1/auth"), gestor()))
            http.perform(post("/api/v1/despachos").header("Authorization", "Bearer " + token).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DESP_ERROR_SCOPE_INSUFICIENTE"));
        String sinRol = jwt("acceso", DISTRITO.toString(), null, List.of("OTRO"), null, 300, CLAVE, URL + "/api/v1/auth");
        for (String ruta : List.of("/api/v1/despachos/simulaciones", "/api/v1/despachos/" + UUID.randomUUID() + "/asignacion")) {
            http.perform(post(ruta).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
            http.perform(post(ruta).header("Authorization", "Bearer " + sinRol).contentType("application/json").content("{}")).andExpect(status().isForbidden());
            http.perform(post(ruta).header("Authorization", "Bearer " + ventas()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        }
        http.perform(get("/api/v1/despachos/pendientes")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/despachos/pendientes").header("Authorization", "Bearer " + sinRol)).andExpect(status().isForbidden());
        assertThat(CONSULTAS_PRODUCTOS).isEmpty(); assertThat(LLAMADAS_RESERVA).isEmpty(); assertThat(contar("despachos")).isZero();
    }

    @Test void simulacionRespetaParametrosYGeneraDatosPorOmisionSinProductosNiEventosExternos() throws Exception {
        http.perform(post("/api/v1/despachos/simulaciones").header("Authorization", "Bearer " + gestor()).contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.fechaProgramada").value("2026-10-16"))
                .andExpect(jsonPath("$.esSimulacion").value(true)).andExpect(jsonPath("$.codigoOperativo", startsWith("DSP-SIM-")))
                .andExpect(jsonPath("$.pesoTotalKg", allOf(greaterThanOrEqualTo(1.0), lessThanOrEqualTo(25.0))));
        simular().andExpect(status().isCreated()).andExpect(jsonPath("$.pesoTotalKg").value(12.5))
                .andExpect(jsonPath("$.volumenTotalM3").value(0.045)).andExpect(jsonPath("$.idZona").value(ZONA.toString()));
        assertThat(CONSULTAS_PRODUCTOS).isEmpty(); assertThat(contar("historial_estados_despacho")).isEqualTo(2);
        assertThat(contar("eventos_salientes")).isZero();
    }

    @Test void simulacionRechazaCargaNoPositivaYZonaInactivaSinEscrituras() throws Exception {
        for (String cuerpo : List.of("{\"pesoKg\":0}", "{\"volumenM3\":-1}", "{\"pesoKg\":\"12.5\"}"))
            http.perform(post("/api/v1/despachos/simulaciones").header("Authorization", "Bearer " + gestor()).contentType("application/json").content(cuerpo))
                    .andExpect(status().isBadRequest());
        sql.sql("UPDATE zonas SET estado='INACTIVO'").update();
        simular().andExpect(status().isUnprocessableEntity()); assertThat(contar("despachos")).isZero();
    }

    @Test void colaFiltraEstadoZonaFechasYReprogramacionesSinConfundirContadorConReintento() throws Exception {
        UUID primero = simulacionId(); UUID reintentoUno = simulacionId(); UUID noIntentado = simulacionId(); UUID asignado = simulacionId();
        sql.sql("UPDATE despachos SET estado='ASIGNADO' WHERE id=:id").param("id", asignado).update();
        sql.sql("UPDATE despachos SET numero_intentos=1 WHERE id=:id").param("id", reintentoUno).update();
        sql.sql("UPDATE despachos SET fecha_programada='2026-10-18' WHERE id=:id").param("id", noIntentado).update();
        for (UUID id : List.of(reintentoUno, noIntentado)) sql.sql("""
                INSERT INTO historial_estados_despacho(despacho_id, tipo_operacion, estado_anterior, estado_nuevo, funcionalidad_origen,
                  ejecutor_tipo, ejecutor_id, origen, datos, registrado_en)
                VALUES (:id, 'TRANSICION', 'FALLIDO', 'PENDIENTE_ASIGNACION', 'F-04', 'USUARIO', 'gestor', 'MANUAL',
                  '{"nuevaFechaProgramada":"2026-10-18"}', now())
                """).param("id", id).update();
        pendientes("tipoIntento", "REINTENTOS").andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(2))
                .andExpect(jsonPath("$.contenido[*].numeroIntentos", containsInAnyOrder(0, 1)))
                .andExpect(jsonPath("$.indicadores.pendientesHoy").value(2)).andExpect(jsonPath("$.indicadores.programadosFuturo").value(1))
                .andExpect(jsonPath("$.indicadores.reintentos").value(2)).andExpect(jsonPath("$.fechaActualServidor").value("2026-10-16"));
        pendientes("tipoIntento", "PRIMER_INTENTO").andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.contenido[0].idDespacho").value(primero.toString()));
        http.perform(get("/api/v1/despachos/pendientes").header("Authorization", "Bearer " + gestor())
                        .queryParam("idZona", ZONA.toString()).queryParam("tipoIntento", "REINTENTOS").queryParam("fechaDesde", "2026-10-18"))
                .andExpect(jsonPath("$.totalElementos").value(1)).andExpect(jsonPath("$.contenido[0].asignable").value(false))
                .andExpect(jsonPath("$.contenido[0].disponibleDesde").value("2026-10-18"));
        pendientes("pagina", "100").andExpect(jsonPath("$.contenido").isEmpty()).andExpect(jsonPath("$.totalElementos").value(3));
        pendientes("idZona", "no-existe").andExpect(jsonPath("$.totalPaginas").value(0)).andExpect(jsonPath("$.contenido").isEmpty());
    }

    @Test void colaRechazaFiltrosDesconocidosRepetidosVaciosFueraDeRangoOFechasInvalidas() throws Exception {
        for (String[] entrada : List.of(new String[]{"pagina", "0"}, new String[]{"tamanoPagina", "101"}, new String[]{"orden", "fecha"},
                new String[]{"fechaDesde", "2026-02-30"}, new String[]{"tipoIntento", "OTRO"}, new String[]{"idZona", ""}))
            pendientes(entrada[0], entrada[1]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_FILTROS_INVALIDOS"));
        http.perform(get("/api/v1/despachos/pendientes").header("Authorization", "Bearer " + gestor()).queryParam("pagina", "1", "2"))
                .andExpect(status().isBadRequest());
    }

    @Test void asignacionConfirmaReservaHistorialVersionYSecuenciaYRepiteResultadoSinReservarOtraVez() throws Exception {
        UUID id = simulacionId();
        String original = asignar(id, "REP-0012").andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ASIGNADO"))
                .andExpect(jsonPath("$.versionDespacho").value(2)).andExpect(jsonPath("$.posicionSecuencia").value(1))
                .andReturn().getResponse().getContentAsString();
        asignar(id, "REP-0012").andExpect(status().isOk()).andExpect(content().json(original));
        asignar(id, "REP-OTRO").andExpect(status().isConflict());
        assertThat(LLAMADAS_RESERVA).hasSize(1); assertThat(RESERVAS).hasSize(1);
        assertThat(contar("historial_estados_despacho")).isEqualTo(2);
        assertThat(sql.sql("SELECT count(*) FROM eventos_salientes WHERE destino='VENTAS'").query(Long.class).single()).isZero();
        assertThat(sql.sql("SELECT count(*) FROM eventos_salientes WHERE destino='OPERACION'").query(Long.class).single()).isEqualTo(1);
        assertThat(LLAMADAS_RESERVA.getFirst().path("requerido").path("paquetes").intValue()).isEqualTo(1);
        pendientes("pagina", "1").andExpect(jsonPath("$.totalElementos").value(0));
    }

    @Test void asignacionRechazaFuturoCapacidadExcedidaNoHabilitadoYReservaAnteriorSinModificarDespacho() throws Exception {
        UUID id = simulacionId();
        sql.sql("UPDATE despachos SET fecha_programada='2026-10-18' WHERE id=:id").param("id", id).update();
        asignar(id, "REP-0012").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_FECHA_PROGRAMADA_FUTURA"));
        assertThat(LLAMADAS_RESERVA).isEmpty();
        sql.sql("UPDATE despachos SET fecha_programada='2026-10-16' WHERE id=:id").param("id", id).update();
        for (String modo : List.of("PESO", "VOLUMEN", "PAQUETES", "INHABILITADO", "ANTERIOR")) {
            MODO_CAPACIDAD.set(modo);
            var resultado = asignar(id, "REP-0012").andExpect(status().is(modo.equals("ANTERIOR") || modo.equals("INHABILITADO") ? 409 : 422));
            if (List.of("PESO", "VOLUMEN", "PAQUETES").contains(modo)) resultado.andExpect(jsonPath("$.detail", containsString(modo.toLowerCase(Locale.ROOT))));
            assertThat(sql.sql("SELECT estado FROM despachos WHERE id=:id").param("id", id).query(String.class).single()).isEqualTo("PENDIENTE_ASIGNACION");
            assertThat(RESERVAS).isEmpty(); assertThat(contar("historial_estados_despacho")).isEqualTo(1);
        }
        MODO_CAPACIDAD.set("OK"); asignar(id, "REP-0012").andExpect(status().isOk());
        assertThat(new HashSet<>(CLAVES_RESERVA)).hasSize(6);
    }

    @Test void respuestaPerdidaDeReservaReutilizaClaveDurableYCargaOriginalHastaConfirmar() throws Exception {
        UUID id = simulacionId(); MODO_CAPACIDAD.set("RESPUESTA_PERDIDA");
        asignar(id, "REP-0012").andExpect(status().isServiceUnavailable());
        assertThat(RESERVAS).hasSize(1);
        assertThat(sql.sql("SELECT estado FROM asignaciones_despacho").query(String.class).single()).isEqualTo("PENDIENTE");
        MODO_CAPACIDAD.set("OK");
        asignacion.recuperarPendientes(); // Fuerza la fecha de recuperación para simular reinicio/espera sin dormir.
        sql.sql("UPDATE asignaciones_despacho SET proximo_intento_en=now()-interval '1 second'").update();
        asignacion.recuperarPendientes();
        assertThat(RESERVAS).hasSize(1); assertThat(new HashSet<>(CLAVES_RESERVA)).hasSize(1);
        assertThat(sql.sql("SELECT estado FROM despachos").query(String.class).single()).isEqualTo("ASIGNADO");
    }

    @Test void conflictoDeVersionDespuesDeReservarCompensaSinHistorialNiEventosDeAsignacion() throws Exception {
        UUID id = simulacionId();
        AL_RESERVAR.set(() -> sql.sql("UPDATE despachos SET estado='CANCELADO', version=version+1 WHERE id=:id").param("id", id).update());
        asignar(id, "REP-0012").andExpect(status().isConflict()).andExpect(jsonPath("$.estadoActual").value("CANCELADO"));
        assertThat(LIBERADAS).hasSize(1); assertThat(contar("historial_estados_despacho")).isEqualTo(1);
        assertThat(contar("eventos_salientes")).isZero();
        assertThat(sql.sql("SELECT estado FROM asignaciones_despacho").query(String.class).single()).isEqualTo("COMPENSADA");
    }

    @Test void falloPersistiendoRevierteCreacionYTrasReservarConservaCompensacionRecuperable() throws Exception {
        UUID id = simulacionId(); bloquearHistorial();
        recibir("PED-ROLLBACK", UUID.randomUUID().toString()).andExpect(status().isServiceUnavailable());
        assertThat(contar("despachos")).isEqualTo(1); assertThat(contar("claves_idempotencia")).isZero();
        FALLAR_LIBERACION.set(true);
        asignar(id, "REP-0012").andExpect(status().isServiceUnavailable());
        assertThat(sql.sql("SELECT estado FROM despachos").query(String.class).single()).isEqualTo("PENDIENTE_ASIGNACION");
        assertThat(sql.sql("SELECT estado FROM asignaciones_despacho").query(String.class).single()).isEqualTo("COMPENSACION_PENDIENTE");
        assertThat(contar("eventos_salientes")).isZero();
        sql.sql("DROP TRIGGER prueba_fallo_historial ON historial_estados_despacho").update(); FALLAR_LIBERACION.set(false);
        sql.sql("UPDATE asignaciones_despacho SET proximo_intento_en=now()-interval '1 second'").update();
        asignacion.recuperarPendientes();
        assertThat(LIBERADAS).hasSize(1); assertThat(LLAMADAS_RESERVA).hasSize(1);
        assertThat(sql.sql("SELECT estado FROM asignaciones_despacho").query(String.class).single()).isEqualTo("COMPENSADA");
    }

    @Test void asignacionesSimultaneasDeDistintosDespachosNoRepitenPosicionDeRuta() throws Exception {
        UUID uno = simulacionId(); UUID dos = simulacionId();
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            var primera = hilos.submit(() -> asignar(uno, "REP-0012").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var segunda = hilos.submit(() -> asignar(dos, "REP-0012").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(List.of(JSON.readTree(primera.get(10, TimeUnit.SECONDS)).path("posicionSecuencia").intValue(),
                    JSON.readTree(segunda.get(10, TimeUnit.SECONDS)).path("posicionSecuencia").intValue())).containsExactlyInAnyOrder(1, 2);
        }
        assertThat(RESERVAS).hasSize(2);
    }

    @Test void dosGestoresSobreUnDespachoNoCreanDosReservasYNoMantienenTransaccionAbiertaDuranteHttp() throws Exception {
        UUID id = simulacionId(); var entrando = new CountDownLatch(1); var continuar = new CountDownLatch(1);
        AL_RESERVAR.set(() -> {
            assertThat(sql.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction'").query(Long.class).single()).isZero();
            entrando.countDown();
            try { continuar.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            var primera = hilos.submit(() -> asignar(id, "REP-0012").andReturn().getResponse().getStatus());
            assertThat(entrando.await(5, TimeUnit.SECONDS)).isTrue();
            asignar(id, "REP-OTRO").andExpect(status().isConflict()); continuar.countDown();
            assertThat(primera.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(RESERVAS).hasSize(1); assertThat(LLAMADAS_RESERVA).hasSize(1);
    }

    @Test void outboxConservaIdEventoYVersionDeProyeccionYSoloNotificaAVentasDespachosReales() throws Exception {
        UUID id = UUID.fromString(JSON.readTree(recibir("PED-EVENTO", UUID.randomUUID().toString()).andReturn().getResponse().getContentAsString()).path("idDespacho").asText());
        sql.sql("UPDATE despachos SET fecha_programada='2026-10-16'").update();
        asignar(id, "REP-0012").andExpect(status().isOk());
        assertThat(EVENTOS_VENTAS).isEmpty(); assertThat(PROYECCIONES).isEmpty();
        FALLAR_VENTAS.set(true); eventos.ejecutar();
        assertThat(PROYECCIONES).hasSize(1); assertThat(PROYECCIONES.getFirst().path("version").intValue()).isEqualTo(2);
        for (int intento = 1; intento < 6; intento++) {
            sql.sql("UPDATE eventos_salientes SET proximo_intento_en=now()-interval '1 second' WHERE destino='VENTAS'").update(); eventos.ejecutar();
        }
        assertThat(EVENTOS_VENTAS).hasSize(6);
        assertThat(EVENTOS_VENTAS.stream().map(evento -> evento.path("idEvento").asText()).distinct()).hasSize(1);
        assertThat(sql.sql("SELECT estado_envio FROM eventos_salientes WHERE destino='VENTAS'").query(String.class).single()).isEqualTo("ENVIO_FALLIDO");
        assertThat(sql.sql("SELECT estado FROM despachos").query(String.class).single()).isEqualTo("ASIGNADO");
    }

    @Test void consultaPaginaDe100SobreDiezMilPendientesConIndicesSinPaginarEnMemoria() throws Exception {
        sql.sql("""
                INSERT INTO despachos(id, pedido_id, codigo_rastreo_interno, codigo_operativo, zona_id, estado, direccion, distrito,
                    peso_kg, volumen_m3, cantidad_paquetes, fecha_programada, en_cola_desde)
                SELECT gen_random_uuid(), 'PED-CARGA-'||n, 'TRK-CARGA-'||n, 'DSP-CARGA-'||n, :zona, 'PENDIENTE_ASIGNACION',
                    'Dirección de prueba', 'San Borja', 1, 0.01, 1, date '2026-10-16', now() FROM generate_series(1,10000) n
                """).param("zona", ZONA).update();
        pendientes("tamanoPagina", "100").andExpect(status().isOk()); // Calentamiento de serialización/JWT.
        long inicio = System.nanoTime();
        pendientes("tamanoPagina", "100").andExpect(status().isOk()).andExpect(jsonPath("$.contenido.length()").value(100))
                .andExpect(jsonPath("$.totalElementos").value(10000));
        long milisegundos = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
        System.out.println("F02: consulta de 100/10000 pendientes = " + milisegundos + " ms");
        assertThat(milisegundos).isLessThan(250);
    }

    @Test void simulacionConZonaSoloPostalPreservaPrecisionSinRedondearCargaACero() throws Exception {
        sql.sql("DELETE FROM zonas_distritos WHERE zona_id=:zona").param("zona", ZONA).update();
        sql.sql("INSERT INTO zonas_codigos_postales(zona_id, distrito_id, codigo_postal) VALUES (:zona, :distrito, '15036')")
                .param("zona", ZONA).param("distrito", DISTRITO).update();
        http.perform(post("/api/v1/despachos/simulaciones").header("Authorization", "Bearer " + gestor()).contentType("application/json")
                .content("{\"pesoKg\":0.0000001,\"volumenM3\":0.000000000001}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.esSimulacion").value(true));
        assertThat(sql.sql("SELECT peso_kg FROM despachos").query(java.math.BigDecimal.class).single()).isEqualByComparingTo("0.0000001");
        assertThat(sql.sql("SELECT volumen_m3 FROM despachos").query(java.math.BigDecimal.class).single()).isEqualByComparingTo("0.000000000001");
        assertThat(sql.sql("SELECT codigo_postal FROM despachos").query(String.class).single()).isEqualTo("15036");
        assertThat(sql.sql("SELECT distrito FROM despachos").query(String.class).single()).isEqualTo("San Borja");
    }

    @Test void simulacionConservaElDistritoDelCodigoPostalSeleccionado() throws Exception {
        UUID otroDistrito = UUID.fromString("44444444-4444-4444-8444-444444444444");
        sql.sql("INSERT INTO distrito(id, nombre) VALUES (:id, 'Ancón')").param("id", otroDistrito).update();
        sql.sql("INSERT INTO zonas_distritos(zona_id, distrito_id) VALUES (:zona, :distrito)")
                .param("zona", ZONA).param("distrito", otroDistrito).update();
        sql.sql("INSERT INTO zonas_codigos_postales(zona_id, distrito_id, codigo_postal) VALUES (:zona, :distrito, '15036')")
                .param("zona", ZONA).param("distrito", DISTRITO).update();
        simular().andExpect(status().isCreated());
        assertThat(sql.sql("SELECT codigo_postal FROM despachos").query(String.class).single()).isEqualTo("15036");
        assertThat(sql.sql("SELECT distrito FROM despachos").query(String.class).single()).isEqualTo("San Borja");
    }

    @Test void unaRespuestaIncoherenteDeReservaConservaLaIntencionYNoConfirmaUnaCapacidadFalsa() throws Exception {
        UUID id = simulacionId(); MODO_CAPACIDAD.set("INCOHERENTE");
        asignar(id, "REP-0012").andExpect(status().isServiceUnavailable());
        assertThat(sql.sql("SELECT estado FROM despachos").query(String.class).single()).isEqualTo("PENDIENTE_ASIGNACION");
        assertThat(sql.sql("SELECT estado FROM asignaciones_despacho").query(String.class).single()).isEqualTo("PENDIENTE");
        assertThat(contar("historial_estados_despacho")).isEqualTo(1);
    }

    @Test void redireccionDeVentasNoSeInterpretaComoExitoNiRevierteLaAsignacionConfirmada() throws Exception {
        UUID id = UUID.fromString(JSON.readTree(recibir("PED-REDIRECCION", UUID.randomUUID().toString()).andReturn().getResponse().getContentAsString()).path("idDespacho").asText());
        sql.sql("UPDATE despachos SET fecha_programada='2026-10-16'").update();
        asignar(id, "REP-0012").andExpect(status().isOk()); REDIRIGIR_VENTAS.set(true); eventos.ejecutar();
        assertThat(sql.sql("SELECT estado_envio FROM eventos_salientes WHERE destino='VENTAS'").query(String.class).single()).isEqualTo("PENDIENTE");
        assertThat(sql.sql("SELECT estado FROM despachos").query(String.class).single()).isEqualTo("ASIGNADO");
        assertThat(EVENTOS_VENTAS).hasSize(1);
    }

    private void bloquearHistorial() {
        sql.sql("CREATE OR REPLACE FUNCTION prueba_fallo_f02() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fallo de prueba'; END; $$").update();
        sql.sql("CREATE TRIGGER prueba_fallo_historial BEFORE INSERT ON historial_estados_despacho FOR EACH ROW EXECUTE FUNCTION prueba_fallo_f02()").update();
    }
    private long contar(String tabla) { return sql.sql("SELECT count(*) FROM " + tabla).query(Long.class).single(); }
    private ResultActions recibir(String pedido, String clave) throws Exception {
        return http.perform(post("/api/v1/despachos").header("Authorization", "Bearer " + ventas()).header("Idempotency-Key", clave)
                .contentType("application/json").content(solicitud(pedido)));
    }
    private ResultActions simular() throws Exception {
        return http.perform(post("/api/v1/despachos/simulaciones").header("Authorization", "Bearer " + gestor()).contentType("application/json")
                .content("{\"idZona\":\"" + ZONA + "\",\"pesoKg\":12.5,\"volumenM3\":0.045}"));
    }
    private UUID simulacionId() throws Exception { return UUID.fromString(JSON.readTree(simular().andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("idDespacho").asText()); }
    private ResultActions pendientes(String campo, String valor) throws Exception { return http.perform(get("/api/v1/despachos/pendientes").header("Authorization", "Bearer " + gestor()).queryParam(campo, valor)); }
    private ResultActions asignar(UUID id, String repartidor) throws Exception {
        return http.perform(post("/api/v1/despachos/" + id + "/asignacion").header("Authorization", "Bearer " + gestor())
                .contentType("application/json").content("{\"idRepartidor\":\"" + repartidor + "\",\"idJornada\":\"JOR-20261016-0012\",\"observacion\":\"Entrega prioritaria\"}"));
    }
    private static String solicitud(String pedido) { return """
            {"idPedido":"%s","destinatario":{"nombre":"Carlos Mendoza","telefono":"+51987654321"},
             "destino":{"direccion":"Av. Javier Prado 2465","distrito":"San Borja"},
             "lineas":[{"sku":"POL-NEG-M","cantidad":3},{"sku":"POL-NEG-M","cantidad":2},{"sku":"ZAP-RUN-42","cantidad":1}]}
            """.formatted(pedido).strip(); }
    private static String gestor() { return jwt("acceso", DISTRITO.toString(), null, List.of("GESTOR_DESPACHO"), null, 300, CLAVE, URL + "/api/v1/auth"); }
    private static String ventas() { return jwt("servicio", "modulo-ventas", "despachos:crear", List.of(), "api-despacho", 300, CLAVE, URL + "/api/v1/auth"); }
    private static String jwt(String tipo, String sub, String scope, List<String> roles, String audiencia, int segundos, RSAKey clave, String emisor) {
        try {
            var claims = new JWTClaimsSet.Builder().subject(sub).issuer(emisor).issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(segundos)))
                    .claim("tipo", tipo).claim("roles", roles);
            if (scope != null) claims.claim("scope", scope);
            if (audiencia != null) claims.audience(audiencia);
            var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(clave.getKeyID()).build(), claims.build());
            token.sign(new RSASSASigner(clave)); return token.serialize();
        } catch (JOSEException error) { throw new IllegalStateException(error); }
    }
    private static RSAKey clave() {
        try { return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate(); }
        catch (JOSEException error) { throw new IllegalStateException(error); }
    }
    private static HttpServer servidor() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); servidor.createContext("/", ProgramacionHttpTests::atender); servidor.start(); return servidor;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    private static void atender(HttpExchange solicitud) throws IOException {
        String ruta = solicitud.getRequestURI().getPath();
        if (ruta.equals("/api/v1/auth/.well-known/openid-configuration")) { responder(solicitud, 200, JSON.writeValueAsString(Map.of("issuer", URL + "/api/v1/auth", "jwks_uri", URL + "/api/v1/auth/.well-known/jwks.json"))); return; }
        if (ruta.equals("/api/v1/auth/.well-known/jwks.json")) { responder(solicitud, 200, JSON.writeValueAsString(Map.of("keys", List.of(CLAVE.toPublicJWK().toJSONObject())))); return; }
        JsonNode cuerpo = JSON.readTree(solicitud.getRequestBody());
        if (ruta.equals("/api/v1/productos/datos-fisicos/consulta")) {
            if (!"Bearer tecnico-productos".equals(solicitud.getRequestHeaders().getFirst("Authorization"))) { responder(solicitud, 403, "{}"); return; }
            CONSULTAS_PRODUCTOS.add(cuerpo);
            String modo = MODO_PRODUCTOS.get();
            if (modo.equals("CAIDA")) { responder(solicitud, 503, "{}"); return; }
            if (modo.equals("LENTO")) try { Thread.sleep(1800); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            if (modo.equals("DESCONOCIDO")) { responder(solicitud, 200, "{\"productos\":[],\"noEncontrados\":[\"POL-NEG-M\"]}"); return; }
            responder(solicitud, 200, """
                    {"productos":[{"sku":"POL-NEG-M","pesoKg":%s,"dimensionesCm":{"largo":30,"ancho":25,"alto":3}},
                      {"sku":"ZAP-RUN-42","pesoKg":1.4,"dimensionesCm":{"largo":35,"ancho":22,"alto":13}}],"noEncontrados":[]}
                    """.formatted(modo.equals("INCOMPLETO") ? "null" : "0.25")); return;
        }
        if (ruta.equals("/internal/v1/capacidad/reservas")) {
            if (!"Bearer tecnico-operacion".equals(solicitud.getRequestHeaders().getFirst("Authorization"))) { responder(solicitud, 403, "{}"); return; }
            String clave = solicitud.getRequestHeaders().getFirst("Idempotency-Key");
            CLAVES_RESERVA.add(clave); LLAMADAS_RESERVA.add(cuerpo);
            String modo = MODO_CAPACIDAD.get();
            if (List.of("PESO", "VOLUMEN", "PAQUETES").contains(modo)) {
                String campo = modo.equals("PESO") ? "pesoKg" : modo.equals("VOLUMEN") ? "volumenM3" : "paquetes";
                responder(solicitud, 422, "{\"code\":\"DESP_ERROR_CAPACIDAD_EXCEDIDA\",\"errores\":[{\"campo\":\"" + campo + "\"}]}"); return;
            }
            if (modo.equals("INHABILITADO") || modo.equals("ANTERIOR")) { responder(solicitud, 409, "{\"code\":\"" + (modo.equals("ANTERIOR") ? "DESP_ERROR_RESERVA_ANTERIOR_ACTIVA" : "DESP_ERROR_REPARTIDOR_NO_HABILITADO") + "\"}"); return; }
            String reserva = RESERVAS.computeIfAbsent(clave, ignorada -> "RES-" + UUID.randomUUID());
            AL_RESERVAR.get().run();
            if (modo.equals("RESPUESTA_PERDIDA")) { responder(solicitud, 503, "{}"); return; }
            if (modo.equals("INCOHERENTE")) { responder(solicitud, 200, "{\"idReserva\":\"RES-INCOMPLETA\",\"estado\":\"CONFIRMADA\",\"capacidadRemanente\":{\"pesoKg\":10,\"volumenM3\":1}}"); return; }
            responder(solicitud, 200, JSON.writeValueAsString(Map.of("idReserva", reserva, "estado", "CONFIRMADA", "capacidadRemanente", Map.of("pesoKg", 100, "volumenM3", 1, "paquetes", 10)))); return;
        }
        if (ruta.endsWith("/liberacion")) {
            if (FALLAR_LIBERACION.get()) { responder(solicitud, 503, "{}"); return; }
            String reserva = ruta.split("/")[5]; LIBERADAS.add(reserva);
            responder(solicitud, 200, JSON.writeValueAsString(Map.of("idReserva", reserva, "idDespacho", cuerpo.path("idDespacho").asText(), "estado", "LIBERADA", "motivoLiberacion", "COMPENSACION", "liberadaEn", "2026-10-17T02:00:00Z"))); return;
        }
        if (ruta.equals("/api/v1/integraciones/despachos/eventos")) {
            if (!"Bearer tecnico-ventas".equals(solicitud.getRequestHeaders().getFirst("Authorization"))) { responder(solicitud, 403, "{}"); return; }
            EVENTOS_VENTAS.add(cuerpo); responder(solicitud, REDIRIGIR_VENTAS.get() ? 302 : FALLAR_VENTAS.get() ? 503 : 200, "{}"); return;
        }
        if (ruta.startsWith("/internal/v1/proyecciones/despachos/")) { PROYECCIONES.add(cuerpo); responder(solicitud, 200, "{}"); return; }
        responder(solicitud, 404, "{}");
    }
    private static void responder(HttpExchange solicitud, int estado, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8); solicitud.getResponseHeaders().set("Content-Type", "application/json");
        solicitud.sendResponseHeaders(estado, bytes.length); solicitud.getResponseBody().write(bytes); solicitud.close();
    }
}
