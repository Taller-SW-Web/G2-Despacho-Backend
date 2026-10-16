package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.ConsultarEntregasFallidas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Servicio y JDBC reales; emisor JWT y API de Operación son dobles identificados. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "SEGURIDAD_URL=http://127.0.0.1:1")
@AutoConfigureMockMvc
class ConsultaFallidosHttpTests {

    private static final String RUTA = "/api/v1/despachos/fallidos";
    private static final String RUTA_CATALOGO = "/api/v1/catalogos/motivos-fallo";
    private static final ExecutorService HILOS = Executors.newVirtualThreadPerTaskExecutor();
    private static final AtomicInteger PETICIONES = new AtomicInteger();
    private static final AtomicReference<String> CREDENCIAL = new AtomicReference<>();
    private static volatile boolean operacionDisponible = true;
    private static final HttpServer OPERACION = crearOperacion();

    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired DataSource fuente;
    @MockitoBean JwtDecoder decodificador;
    @MockitoSpyBean ConsultarEntregasFallidas consulta;

    @DynamicPropertySource
    static void configurarOperacion(DynamicPropertyRegistry propiedades) {
        propiedades.add("despacho.operacion.url", () -> "http://127.0.0.1:" + OPERACION.getAddress().getPort());
        propiedades.add("despacho.operacion.token-servicio", () -> "servicio-operacion-prueba");
    }

    @BeforeEach
    void preparar() {
        new ResourceDatabasePopulator(new ClassPathResource("incidencias/esquema-lectura-fallidos.sql")).execute(fuente);
        sql.sql("TRUNCATE intentos_entrega, despachos, motivos_fallo").update();
        sql.sql("UPDATE parametros_configuracion SET valor = '2' WHERE clave = 'MAXIMO_INTENTOS'").update();
        sql.sql("""
                INSERT INTO motivos_fallo (codigo, etiqueta, seleccionable_en_campo, consume_intento, activo)
                VALUES ('NO_INTENTADO', 'No intentado por fin de jornada', false, false, true)
                """).update();
        for (int numero = 1; numero <= 12; numero++) sembrar(numero);
        operacionDisponible = true;
        PETICIONES.set(0);
        CREDENCIAL.set(null);
        clearInvocations(consulta);
        when(decodificador.decode("gestor")).thenReturn(token("acceso", List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("otro")).thenReturn(token("acceso", List.of("OTRO_ROL")));
        when(decodificador.decode("servicio")).thenReturn(token("servicio", List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("invalido")).thenThrow(new BadJwtException("Token de prueba rechazado"));
    }

    @Test
    void integraEndpointCasoDeUsoPostgresqlYHttpOperativoConTotalesAntesDePaginar() throws Exception {
        http.perform(get(RUTA).header("Authorization", "Bearer gestor").queryParam("pagina", "3").queryParam("tamanoPagina", "2"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.pagina").value(3)).andExpect(jsonPath("$.totalElementos").value(12))
                .andExpect(jsonPath("$.totalPaginas").value(6)).andExpect(jsonPath("$.contenido.length()").value(2))
                .andExpect(jsonPath("$.indicadores.pendientesRetorno").value(7))
                .andExpect(jsonPath("$.indicadores.recibidosCentro").value(5))
                .andExpect(jsonPath("$.indicadores.retornosAtrasados").value(2))
                .andExpect(jsonPath("$.contenido[0].idDespacho").value(id(5).toString()))
                .andExpect(jsonPath("$.contenido[1].idDespacho").value(id(6).toString()))
                .andExpect(jsonPath("$.contenido[1].numeroIntentos").value(1))
                .andExpect(jsonPath("$.contenido[1].consumeIntento").value(false))
                .andExpect(jsonPath("$.contenido[1].retornoAtrasado").value(true))
                .andExpect(jsonPath("$.contenido[1].repartidor.nombre").value("Nombre de prueba"))
                .andExpect(jsonPath("$.contenido[0].comentario").doesNotExist())
                .andExpect(jsonPath("$.contenido[0].direccion").doesNotExist())
                .andExpect(jsonPath("$.contenido[0].telefono").doesNotExist())
                .andExpect(jsonPath("$.contenido[0].urlEvidencia").doesNotExist());
        assertThat(PETICIONES.get()).isEqualTo(2);
        assertThat(CREDENCIAL.get()).isEqualTo("Bearer servicio-operacion-prueba");
    }

    @Test
    void rechazaParametrosCrudosInvalidosSinConsultarOperacion() throws Exception {
        String[][] invalidos = {
                {"pagina", "1", "2"}, {"orden", "fecha"}, {"pagina", ""}, {"pagina", "1.5"},
                {"pagina", "2147483648"}, {"tamanoPagina", "101"}, {"motivo", " "},
                {"motivo", "NO_EXISTE"}, {"fechaDesde", "2026-02-30"}, {"fechaHasta", "16/10/2026"},
                {"soloRetornosAtrasados", "TRUE"}, {"soloRetornosAtrasados", "1"}, {"recepcion", "OTRO"}
        };
        for (String[] invalido : invalidos) {
            String[] valores = java.util.Arrays.copyOfRange(invalido, 1, invalido.length);
            http.perform(get(RUTA).header("Authorization", "Bearer gestor").queryParam(invalido[0], valores))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("DESP_ERROR_FILTROS_INVALIDOS"))
                    .andExpect(jsonPath("$.errores").isNotEmpty());
        }
        http.perform(get(RUTA).header("Authorization", "Bearer gestor").queryParam("recepcion", "RECIBIDO_EN_CENTRO")
                        .queryParam("soloRetornosAtrasados", "true"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_FILTROS_INVALIDOS"));
        http.perform(get(RUTA).header("Authorization", "Bearer gestor").queryParam("fechaDesde", "2026-10-17")
                        .queryParam("fechaHasta", "2026-10-16"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_FILTROS_INVALIDOS"));
        assertThat(PETICIONES.get()).isZero();
    }

    @Test
    void exigeIdentidadHumanaYRolAntesDeValidarFiltrosOEjecutarElCasoDeUso() throws Exception {
        http.perform(get(RUTA).queryParam("pagina", "invalida")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
        http.perform(get(RUTA).header("Authorization", "Bearer invalido")).andExpect(status().isUnauthorized());
        for (String token : List.of("otro", "servicio")) {
            http.perform(get(RUTA).header("Authorization", "Bearer " + token).queryParam("pagina", "invalida"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.contenido").doesNotExist());
        }
        verifyNoInteractions(consulta);
        assertThat(PETICIONES.get()).isZero();
    }

    @Test
    void conservaNulosYDatosLocalesSiOperacionFallaYResponde503AlPedirSoloAtrasados() throws Exception {
        operacionDisponible = false;
        http.perform(get(RUTA).header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(12))
                .andExpect(jsonPath("$.indicadores.retornosAtrasados").hasJsonPath())
                .andExpect(jsonPath("$.indicadores.retornosAtrasados").value(nullValue()))
                .andExpect(jsonPath("$.indicadores.retornosSinJornadaConfirmada").value(7))
                .andExpect(jsonPath("$.contenido[0].retornoAtrasado").value(false))
                .andExpect(jsonPath("$.contenido[0].repartidor.nombre").hasJsonPath())
                .andExpect(jsonPath("$.contenido[0].repartidor.nombre").value(nullValue()));
        http.perform(get(RUTA).header("Authorization", "Bearer gestor").queryParam("recepcion", "PENDIENTE_RETORNO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contenido[0].retornoAtrasado").hasJsonPath())
                .andExpect(jsonPath("$.contenido[0].retornoAtrasado").value(nullValue()));
        http.perform(get(RUTA).header("Authorization", "Bearer gestor").queryParam("soloRetornosAtrasados", "true"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_JORNADAS_NO_DISPONIBLES"))
                .andExpect(jsonPath("$.contenido").doesNotExist());
    }

    @Test
    void distingueVacioInconsistenciaYLecturaNoDisponibleSinExponerSql() throws Exception {
        sql.sql("UPDATE parametros_configuracion SET valor = '0' WHERE clave = 'MAXIMO_INTENTOS'").update();
        http.perform(get(RUTA).header("Authorization", "Bearer gestor")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_INCONSISTENTES"));
        sql.sql("UPDATE parametros_configuracion SET valor = '2' WHERE clave = 'MAXIMO_INTENTOS'").update();
        sql.sql("TRUNCATE intentos_entrega, despachos").update();
        http.perform(get(RUTA).header("Authorization", "Bearer gestor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido").isEmpty()).andExpect(jsonPath("$.totalPaginas").value(0));
        assertThat(PETICIONES.get()).isZero();
        sql.sql("ALTER TABLE despachos RENAME TO despachos_no_disponible").update();
        try {
            http.perform(get(RUTA).header("Authorization", "Bearer gestor")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DESP_ERROR_CONSULTA_NO_DISPONIBLE"))
                    .andExpect(content().string(not(containsString("SELECT"))))
                    .andExpect(jsonPath("$.contenido").doesNotExist());
        } finally {
            sql.sql("ALTER TABLE despachos_no_disponible RENAME TO despachos").update();
        }
    }

    @Test
    void devuelveCatalogoCompletoOrdenadoConMotivosHistoricosYNoIntentado() throws Exception {
        sql.sql("""
                INSERT INTO motivos_fallo (codigo, etiqueta, seleccionable_en_campo, consume_intento, activo)
                VALUES ('HISTORICO', 'Motivo retirado', true, true, false),
                       ('AUSENTE', 'Destinatario ausente', true, true, true)
                """).update();
        http.perform(get(RUTA_CATALOGO).header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.motivos.length()").value(3))
                .andExpect(jsonPath("$.motivos[0].codigo").value("AUSENTE"))
                .andExpect(jsonPath("$.motivos[0].etiqueta").value("Destinatario ausente"))
                .andExpect(jsonPath("$.motivos[0].seleccionableEnCampo").value(true))
                .andExpect(jsonPath("$.motivos[0].consumeIntento").value(true))
                .andExpect(jsonPath("$.motivos[1].codigo").value("HISTORICO"))
                .andExpect(jsonPath("$.motivos[1].activo").value(false))
                .andExpect(jsonPath("$.motivos[2].codigo").value("NO_INTENTADO"))
                .andExpect(jsonPath("$.motivos[2].seleccionableEnCampo").value(false))
                .andExpect(jsonPath("$.motivos[2].consumeIntento").value(false));
        assertThat(PETICIONES.get()).isZero();
    }

    @Test
    void protegeCatalogoAntesDeLeerloYNormalizaIndisponibilidad() throws Exception {
        sql.sql("ALTER TABLE motivos_fallo RENAME TO motivos_fallo_no_disponible").update();
        try {
            http.perform(get(RUTA_CATALOGO)).andExpect(status().isUnauthorized());
            http.perform(get(RUTA_CATALOGO).header("Authorization", "Bearer invalido"))
                    .andExpect(status().isUnauthorized());
            for (String token : List.of("otro", "servicio")) {
                http.perform(get(RUTA_CATALOGO).header("Authorization", "Bearer " + token))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.motivos").doesNotExist());
            }
            http.perform(get(RUTA_CATALOGO).header("Authorization", "Bearer gestor"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DESP_ERROR_CONSULTA_NO_DISPONIBLE"))
                    .andExpect(jsonPath("$.motivos").doesNotExist())
                    .andExpect(content().string(not(containsString("SELECT"))));
        } finally {
            sql.sql("ALTER TABLE motivos_fallo_no_disponible RENAME TO motivos_fallo").update();
        }
        assertThat(PETICIONES.get()).isZero();
    }

    private void sembrar(int numero) {
        boolean recibido = numero <= 5;
        OffsetDateTime fecha = OffsetDateTime.parse(recibido ? "2026-10-15T18:00:00Z" : "2026-10-14T18:00:00Z");
        sql.sql("""
                INSERT INTO despachos (id, estado, version, codigo_rastreo_interno, numero_intentos, ultima_incidencia_id)
                VALUES (:id, 'FALLIDO', 4, :codigo, 1, :incidencia)
                """).param("id", id(numero)).param("codigo", "TRK-" + numero).param("incidencia", id(100 + numero)).update();
        sql.sql("""
                INSERT INTO intentos_entrega (id, despacho_id, resultado, motivo_codigo, consume_intento,
                    repartidor_id_externo, asignacion_diaria_id_externa, registrado_en, recibido_en_centro_en)
                VALUES (:id, :despacho, 'FALLIDO', 'NO_INTENTADO', false, :repartidor, :jornada, :fecha, :recepcion)
                """).param("id", id(100 + numero)).param("despacho", id(numero)).param("repartidor", id(200 + numero))
                .param("jornada", id(300 + numero)).param("fecha", fecha).param("recepcion", recibido ? fecha : null).update();
    }

    private Jwt token(String tipo, List<String> roles) {
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject(id(999).toString())
                .claim("tipo", tipo).claim("roles", roles).audience(List.of("api-despacho"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    private static HttpServer crearOperacion() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.setExecutor(HILOS);
            servidor.createContext("/internal/v1/operacion/consulta-incidencias", ConsultaFallidosHttpTests::responderOperacion);
            servidor.start();
            return servidor;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void responderOperacion(HttpExchange intercambio) throws IOException {
        PETICIONES.incrementAndGet();
        CREDENCIAL.set(intercambio.getRequestHeaders().getFirst("Authorization"));
        int estado = !"Bearer servicio-operacion-prueba".equals(CREDENCIAL.get()) ? 403 : operacionDisponible ? 200 : 503;
        byte[] respuesta = "{}".getBytes(StandardCharsets.UTF_8);
        if (estado == 200) {
            var cuerpo = JsonPath.parse(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            List<String> idsRepartidor = cuerpo.read("$.idsRepartidor");
            List<String> idsJornada = cuerpo.read("$.idsJornada");
            List<Map<String, Object>> jornadas = new ArrayList<>();
            for (String idJornada : idsJornada) {
                boolean cerrada = idJornada.equals(id(306).toString()) || idJornada.equals(id(307).toString());
                Map<String, Object> jornada = new java.util.LinkedHashMap<>();
                jornada.put("idJornada", idJornada);
                jornada.put("estado", cerrada ? "CERRADA" : "ACTIVA");
                jornada.put("cerradaEn", cerrada ? "2026-10-15T23:00:00Z" : null);
                jornadas.add(jornada);
            }
            respuesta = JsonMapper.builder().build().writeValueAsBytes(Map.of(
                    "repartidores", idsRepartidor.stream().map(id -> Map.of("idRepartidor", id, "nombre", "Nombre de prueba")).toList(),
                    "jornadas", jornadas, "consultadoEn", "2026-10-16T14:00:00Z"));
        }
        intercambio.getResponseHeaders().set("Content-Type", "application/json");
        intercambio.sendResponseHeaders(estado, respuesta.length);
        try (var salida = intercambio.getResponseBody()) { salida.write(respuesta); }
        intercambio.close();
    }

    private static UUID id(long numero) { return new UUID(0, numero); }

    @AfterAll
    static void cerrarOperacion() {
        OPERACION.stop(0);
        HILOS.close();
    }
}
