package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LectorDatosOperativos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL y HTTP reales; identidad y proveedor F-06 controlados explícitamente. */
@SpringBootTest(properties={"SEGURIDAD_URL=http://127.0.0.1:1", "despacho.programacion.recuperacion-habilitada=false",
        "despacho.recepcion.procesador-habilitado=false"})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class CancelacionHttpTests {
    static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    static final HttpServer SERVIDOR = servidor();
    static final Map<String, JsonNode> LIBERACIONES = new ConcurrentHashMap<>();
    static final List<String> REFERENCIAS = new CopyOnWriteArrayList<>();
    static final AtomicReference<String> MODO = new AtomicReference<>("OK");
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired AlmacenCancelaciones almacen;
    @Autowired PuertoLiberacionCancelacion capacidad;
    @MockitoBean JwtDecoder identidad;
    @MockitoBean LectorDatosOperativos operacion;
    final UUID despacho = UUID.randomUUID(), repartidor = UUID.randomUUID(), jornada = UUID.randomUUID(), reserva = UUID.randomUUID();
    final UUID otraParada = UUID.randomUUID();

    @DynamicPropertySource static void configurar(DynamicPropertyRegistry p) {
        p.add("despacho.operacion.url", () -> "http://127.0.0.1:" + SERVIDOR.getAddress().getPort());
        p.add("despacho.operacion.token-servicio", () -> "capacidad-prueba");
    }
    @AfterAll static void detener() { SERVIDOR.stop(0); }
    @BeforeEach void preparar() {
        for (String tabla : List.of("despachos", "historial_estados_despacho", "eventos_salientes", "cancelaciones_despacho"))
            sql.sql("DROP TRIGGER IF EXISTS fallo_cancelacion ON " + tabla).update();
        sql.sql("TRUNCATE despachos CASCADE").update();
        LIBERACIONES.clear(); REFERENCIAS.clear(); MODO.set("OK");
        when(identidad.decode("ventas")).thenReturn(token("servicio", "modulo-ventas", List.of(), "despachos:cancelar"));
        when(identidad.decode("gestor")).thenReturn(token("acceso", "gestor", List.of("GESTOR_DESPACHO"), "despachos:cancelar"));
        when(identidad.decode("ajeno")).thenReturn(token("servicio", "otro-modulo", List.of(), "despachos:cancelar"));
        when(identidad.decode("sin-scope")).thenReturn(token("servicio", "modulo-ventas", List.of(), "despachos:crear"));
        when(identidad.decode("servicio")).thenReturn(token("servicio", "modulo-despacho", List.of(), "gestion:despachos:transicionar"));
        when(operacion.consultar(any(), any())).thenReturn(new DatosOperativos(Map.of(), Map.of(jornada.toString(),
                new DatosOperativos.Jornada(jornada.toString(), DatosOperativos.EstadoJornada.ACTIVA, null, Instant.now()))));
        insertar(despacho, "ASIGNADO", 1);
        insertar(otraParada, "ASIGNADO", 2);
    }
    @Test void cancelaAsignadoConAuditoriaEventoYProyeccionRetirandoSoloEsaParada() throws Exception {
        String clave = UUID.randomUUID().toString();
        String respuesta = enviar(clave, cuerpo()).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CANCELADO"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        assertThat(numero("SELECT version FROM despachos WHERE id='"+despacho+"'")).isEqualTo(2);
        assertThat(numero("SELECT count(*) FROM historial_estados_despacho WHERE tipo_operacion='CANCELACION_POR_ANULACION' AND ejecutor_id='modulo-ventas' AND ejecutor_tipo='SERVICIO'")).isEqualTo(1);
        assertThat(texto("SELECT payload->>'estado' FROM eventos_salientes WHERE destino='OPERACION'")).isEqualTo("CANCELADO");
        assertThat(texto("SELECT tipo_evento FROM eventos_salientes WHERE destino='VENTAS'")).isEqualTo("DESPACHO_CANCELADO");
        http.perform(get(rutaJornada()).header("Authorization", "Bearer gestor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.despachos.length()").value(1)).andExpect(jsonPath("$.despachos[0].posicion").value(2));
        assertThat(numero("SELECT secuencia_ruta FROM despachos WHERE id='"+despacho+"'")).isEqualTo(1);
        enviar(clave, cuerpo()).andExpect(status().isOk()).andExpect(content().json(respuesta));
        enviar(clave, cuerpo().replace("PEDIDO_ANULADO", "OTRO")).andExpect(status().isConflict());
        enviar(UUID.randomUUID().toString(), cuerpo()).andExpect(status().isConflict());
        assertThat(REFERENCIAS).containsExactly(reserva.toString());
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isEqualTo(2);
    }
    @Test void pendienteSaleDeColaSinInvocarF06YSimuladoNoPublicaAVentas() throws Exception {
        sql.sql("UPDATE despachos SET estado='PENDIENTE_ASIGNACION',es_simulado=true,repartidor_id_externo=NULL,asignacion_diaria_id_externa=NULL,reserva_capacidad_id_externa=NULL,secuencia_ruta=NULL WHERE id=:id")
                .param("id", despacho).update();
        enviar(UUID.randomUUID().toString(), cuerpo()).andExpect(status().isOk());
        http.perform(get("/api/v1/despachos/pendientes").header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(0));
        assertThat(REFERENCIAS).isEmpty();
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isZero();
    }
    @Test void fallidoMarcaAnulacionVersionaYRepiteResultadoInclusoTrasCerrar() throws Exception {
        estado("FALLIDO"); String clave = UUID.randomUUID().toString();
        String original = enviar(clave, cuerpo()).andExpect(status().isAccepted()).andExpect(jsonPath("$.estado").value("FALLIDO"))
                .andReturn().getResponse().getContentAsString();
        assertThat(numero("SELECT count(*) FROM despachos WHERE pedido_anulado_en IS NOT NULL")).isEqualTo(1);
        assertThat(numero("SELECT version FROM despachos WHERE id='"+despacho+"'")).isEqualTo(2);
        enviar(UUID.randomUUID().toString(), cuerpo()).andExpect(status().isAccepted()).andExpect(content().json(original));
        assertThat(numero("SELECT count(*) FROM historial_estados_despacho")).isEqualTo(1);
        estado("DEVUELTO_A_ORIGEN");
        enviar(clave, cuerpo()).andExpect(status().isAccepted()).andExpect(content().json(original));
        assertThat(REFERENCIAS).isEmpty(); assertThat(numero("SELECT count(*) FROM eventos_salientes")).isZero();
    }
    @Test void rechazaEstadosIncompatiblesSinTocarReservaNiPersistirUnaDecision() throws Exception {
        for (String estado : List.of("EN_CAMINO", "ENTREGADO", "CANCELADO", "DEVUELTO_A_ORIGEN")) {
            estado(estado);
            enviar(UUID.randomUUID().toString(), cuerpo()).andExpect(status().isConflict()).andExpect(jsonPath("$.estadoActual").value(estado));
        }
        assertThat(REFERENCIAS).isEmpty(); assertThat(numero("SELECT count(*) FROM cancelaciones_despacho")).isZero();
    }
    @Test void perdidaTimeoutYRespuestaInvalidaConservanCercoHastaRecuperarLaMismaReserva() throws Exception {
        for (String modo : List.of("PERDIDA", "LENTO", "INVALIDA", "MOTIVO_AJENO", "404", "403")) {
            preparar(); MODO.set(modo); String clave = UUID.randomUUID().toString();
            String antes = http.perform(get(rutaJornada()).header("Authorization", "Bearer gestor")).andReturn().getResponse().getHeader("ETag");
            enviar(clave, cuerpo()).andExpect(status().isServiceUnavailable());
            assertThat(texto("SELECT estado FROM cancelaciones_despacho")).isEqualTo("PENDIENTE");
            assertThat(texto("SELECT estado FROM despachos WHERE id='"+despacho+"'")).isEqualTo("ASIGNADO");
            var ruta = http.perform(get(rutaJornada()).header("Authorization", "Bearer gestor")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.despachos[0].cancelacionEnProceso").value(true)).andReturn().getResponse();
            assertThat(ruta.getHeader("ETag")).isNotEqualTo(antes);
            traslado().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_CANCELACION_EN_PROCESO"));
            http.perform(post("/api/v1/despachos/"+despacho+"/reasignacion").header("Authorization", "Bearer gestor")
                    .header("Idempotency-Key", UUID.randomUUID()).header("If-Match", "\"1\"").contentType("application/json")
                    .content(JSON.writeValueAsString(Map.of("idNuevoRepartidor", UUID.randomUUID(), "idNuevaJornada", UUID.randomUUID(), "motivo", "Cambio"))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_CANCELACION_EN_PROCESO"));
            http.perform(put("/api/v1/jornadas/"+jornada+"/secuencia").header("Authorization", "Bearer gestor")
                    .header("Idempotency-Key", UUID.randomUUID()).header("If-Match", ruta.getHeader("ETag")).contentType("application/json")
                    .content(JSON.writeValueAsString(Map.of("despachos", List.of(Map.of("idDespacho", despacho, "posicion", 2), Map.of("idDespacho", otraParada, "posicion", 1))))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_CANCELACION_EN_PROCESO"));
            MODO.set("OK"); enviar(clave, cuerpo()).andExpect(status().isOk());
            assertThat(REFERENCIAS).hasSize(2).allMatch(reserva.toString()::equals);
        }
    }
    @Test void falloEnCadaEscrituraLocalNoPierdeCercoNiRecreaLaReservaLiberada() throws Exception {
        for (String tabla : List.of("despachos", "historial_estados_despacho", "eventos_salientes", "cancelaciones_despacho")) {
            preparar();
            sql.sql("CREATE OR REPLACE FUNCTION fallo_cancelacion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fallo inducido'; END $$").update();
            String condicion = tabla.equals("cancelaciones_despacho") ? "WHEN (NEW.estado='CONFIRMADA') " : "";
            sql.sql("CREATE TRIGGER fallo_cancelacion BEFORE "+(Set.of("despachos", "cancelaciones_despacho").contains(tabla)?"UPDATE":"INSERT")+" ON "+tabla+" FOR EACH ROW "+condicion+"EXECUTE FUNCTION fallo_cancelacion()").update();
            String clave = UUID.randomUUID().toString();
            enviar(clave, cuerpo()).andExpect(status().isServiceUnavailable());
            assertThat(LIBERACIONES).hasSize(1);
            assertThat(texto("SELECT estado FROM cancelaciones_despacho")).isEqualTo("PENDIENTE");
            assertThat(numero("SELECT count(*) FROM historial_estados_despacho")).isZero();
            assertThat(numero("SELECT count(*) FROM eventos_salientes")).isZero();
            traslado().andExpect(status().isConflict());
            sql.sql("DROP TRIGGER fallo_cancelacion ON "+tabla).update();
            enviar(clave, cuerpo()).andExpect(status().isOk());
            assertThat(LIBERACIONES).hasSize(1);
        }
    }
    @Test void coordinadorNuevoRecuperaPersistenciaTrasVencerLeaseSinRetirarCerco() throws Exception {
        MODO.set("PERDIDA"); enviar(UUID.randomUUID().toString(), cuerpo()).andExpect(status().isServiceUnavailable());
        sql.sql("UPDATE cancelaciones_despacho SET procesando_hasta=now()-interval '1 second',proximo_intento_en=now()-interval '1 second'").update();
        new CancelarDespacho(almacen, capacidad, Clock.systemUTC()).recuperarPendientes();
        assertThat(texto("SELECT estado FROM cancelaciones_despacho")).isEqualTo("CONFIRMADA");
        assertThat(LIBERACIONES).hasSize(1); assertThat(REFERENCIAS).containsExactly(reserva.toString(), reserva.toString());
    }
    @Test void mismaClaveConcurrenteSoloLiberaUnaReservaYDevuelveElOriginalAlReintentar() throws Exception {
        String clave = UUID.randomUUID().toString();
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            var inicio = new CountDownLatch(1);
            Callable<Integer> tarea = () -> { inicio.await(); return enviar(clave, cuerpo()).andReturn().getResponse().getStatus(); };
            var a = hilos.submit(tarea); var b = hilos.submit(tarea); inicio.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).allMatch(e -> e == 200 || e == 409).contains(200);
        }
        enviar(clave, cuerpo()).andExpect(status().isOk());
        assertThat(LIBERACIONES).hasSize(1); assertThat(numero("SELECT count(*) FROM historial_estados_despacho")).isEqualTo(1);
    }
    @Test void trasladoGanadorImpideLiberacionYDosCancelacionesDistintasNoDuplicanEfectos() throws Exception {
        traslado().andExpect(status().isOk());
        enviar(UUID.randomUUID().toString(), cuerpo()).andExpect(status().isConflict()); assertThat(REFERENCIAS).isEmpty();
        preparar();
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> tarea = () -> enviar(UUID.randomUUID().toString(), cuerpo()).andReturn().getResponse().getStatus();
            var a = hilos.submit(tarea); var b = hilos.submit(tarea);
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(LIBERACIONES).hasSize(1);
    }
    @Test void asignacionOReasignacionPendientesNoPermitenCancelarUnaReservaIncierta() throws Exception {
        sql.sql("INSERT INTO asignaciones_despacho(id,despacho_id,version_origen,ejecutor_id,id_repartidor,id_jornada,peso_requerido,volumen_requerido,paquetes_requeridos,estado) VALUES (:id,:despacho,1,'gestor','r','j',2,0.01,1,'PENDIENTE')")
                .param("id", UUID.randomUUID()).param("despacho", despacho).update();
        enviar(UUID.randomUUID().toString(), cuerpo()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_ASIGNACION_EN_PROCESO"));
        sql.sql("UPDATE asignaciones_despacho SET estado='COMPENSACION_PENDIENTE'").update();
        enviar(UUID.randomUUID().toString(), cuerpo()).andExpect(status().isConflict());
        sql.sql("DELETE FROM asignaciones_despacho").update();
        sql.sql("INSERT INTO reasignaciones_despacho(id,despacho_id,ejecutor_id,clave,solicitud,intencion,estado) VALUES (:id,:despacho,'gestor',:clave,'huella','{}','PENDIENTE')")
                .param("id", UUID.randomUUID()).param("despacho", despacho).param("clave", UUID.randomUUID()).update();
        enviar(UUID.randomUUID().toString(), cuerpo()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_REASIGNACION_EN_PROCESO"));
        assertThat(REFERENCIAS).isEmpty();
    }
    @Test void validaIdentidadCabeceraCuerpoYExistenciaAntesDeProducirEfectos() throws Exception {
        http.perform(post(ruta()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        for (String token : List.of("gestor", "ajeno", "sin-scope", "servicio"))
            http.perform(post(ruta()).header("Authorization", "Bearer "+token).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        for (String clave : List.of("", "IDEM-12345")) enviar(clave, cuerpo()).andExpect(status().isBadRequest());
        for (String cuerpo : List.of("{}", "[]", "{", "{\"motivo\":1}", "{\"motivo\":\"  \"}", "{\"motivo\":\"x\",\"extra\":true}",
                JSON.writeValueAsString(Map.of("motivo", "x".repeat(129))), JSON.writeValueAsString(Map.of("motivo", "x", "observacion", "x".repeat(501)))))
            enviar(UUID.randomUUID().toString(), cuerpo).andExpect(status().isBadRequest());
        http.perform(post(ruta()+"?extra=1").header("Authorization", "Bearer ventas").header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content(cuerpo())).andExpect(status().isBadRequest());
        http.perform(post("/api/v1/despachos/pedidos/inexistente/cancelacion").header("Authorization", "Bearer ventas").header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content(cuerpo())).andExpect(status().isNotFound());
        assertThat(REFERENCIAS).isEmpty(); assertThat(numero("SELECT count(*) FROM cancelaciones_despacho")).isZero();
    }
    @Test void eventoReintentaConMismaIdentidadSinRevertirCancelacionNiVolverALiberar() throws Exception {
        enviar(UUID.randomUUID().toString(), cuerpo()).andExpect(status().isOk());
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var cuerpos = new CopyOnWriteArrayList<String>();
        var estado = new java.util.concurrent.atomic.AtomicInteger(503);
        servidor.createContext("/api/v1/integraciones/despachos/eventos", peticion -> {
            cuerpos.add(new String(peticion.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            peticion.sendResponseHeaders(estado.get(), -1); peticion.close();
        });
        servidor.start();
        try {
            var cliente = org.springframework.web.client.RestClient.builder().baseUrl("http://127.0.0.1:"+servidor.getAddress().getPort()).build();
            // La proyección se verifica separadamente; este receptor controlado solo acredita el webhook.
            sql.sql("UPDATE eventos_salientes SET estado_envio='ENVIADO' WHERE destino='OPERACION'").update();
            var entrega = new com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure.EntregaEventosProgramacion(
                    sql, JSON, cliente, () -> {
                        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        return "credencial-sintetica";
                    }, cliente, () -> "no-se-usa");
            entrega.ejecutar();
            assertThat(texto("SELECT estado_envio FROM eventos_salientes WHERE destino='VENTAS'")).isEqualTo("PENDIENTE");
            estado.set(204); sql.sql("UPDATE eventos_salientes SET proximo_intento_en=now() WHERE destino='VENTAS'").update();
            entrega.ejecutar();
            assertThat(texto("SELECT estado_envio FROM eventos_salientes WHERE destino='VENTAS'")).isEqualTo("ENVIADO");
            assertThat(cuerpos).hasSize(2); assertThat(cuerpos.get(1)).isEqualTo(cuerpos.getFirst());
            assertThat(JSON.readTree(cuerpos.getFirst()).path("tipo").asText()).isEqualTo("DESPACHO_CANCELADO");
            assertThat(texto("SELECT estado FROM despachos WHERE id='"+despacho+"'")).isEqualTo("CANCELADO");
            assertThat(REFERENCIAS).containsExactly(reserva.toString());
        } finally { servidor.stop(0); }
    }
    private String cuerpo() { return "{\"motivo\":\"PEDIDO_ANULADO\",\"observacion\":\"Anulación de prueba\"}"; }
    private String ruta() { return "/api/v1/despachos/pedidos/"+despacho+"/cancelacion"; }
    private String rutaJornada() { return "/api/v1/jornadas/"+jornada+"/ruta"; }
    private ResultActions enviar(String clave, String cuerpo) throws Exception {
        return http.perform(post(ruta()).header("Authorization", "Bearer ventas").header("Idempotency-Key", clave).contentType("application/json").content(cuerpo));
    }
    private ResultActions traslado() throws Exception {
        return http.perform(post("/internal/v1/despachos/"+despacho+"/transiciones").header("Authorization", "Bearer servicio").header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content(JSON.writeValueAsString(Map.of("estadoDestino", "EN_CAMINO", "idRepartidor", repartidor, "idJornada", jornada, "ejecutor", repartidor, "origen", "MANUAL"))));
    }
    private void estado(String estado) { sql.sql("UPDATE despachos SET estado=:estado WHERE id=:id").param("id", despacho).param("estado", estado).update(); }
    private void insertar(UUID id, String estado, int posicion) {
        sql.sql("""
                INSERT INTO despachos(id,pedido_id,codigo_operativo,codigo_rastreo_interno,es_simulado,estado,destinatario_nombre,
                    destinatario_telefono,direccion,distrito,peso_kg,volumen_m3,cantidad_paquetes,fecha_programada,
                    repartidor_id_externo,asignacion_diaria_id_externa,reserva_capacidad_id_externa,secuencia_ruta)
                VALUES (:id,:pedido,:pedido,:pedido,false,:estado,'Prueba','999111222','Dirección sintética','Lima',2,0.01,1,current_date,
                    :repartidor,:jornada,:reserva,:posicion)
                """).param("id", id).param("pedido", id.toString()).param("estado", estado).param("repartidor", repartidor.toString())
                .param("jornada", jornada.toString()).param("reserva", reserva.toString()).param("posicion", posicion).update();
    }
    private long numero(String consulta) { return sql.sql(consulta).query(Long.class).single(); }
    private String texto(String consulta) { return sql.sql(consulta).query(String.class).single(); }
    private Jwt token(String tipo, String sub, List<String> roles, String scope) { return Jwt.withTokenValue(tipo).header("alg", "RS256").subject(sub)
            .claim("tipo", tipo).claim("roles", roles).claim("scope", scope).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build(); }
    private static HttpServer servidor() {
        try {
            var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            servidor.createContext("/internal/v1/capacidad/reservas/", CancelacionHttpTests::liberar); servidor.start(); return servidor;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static void liberar(HttpExchange solicitud) throws IOException {
        if (!"Bearer capacidad-prueba".equals(solicitud.getRequestHeaders().getFirst("Authorization"))) { responder(solicitud, 403, "{}"); return; }
        String referencia = solicitud.getRequestURI().getPath().split("/")[5]; REFERENCIAS.add(referencia);
        var cuerpo = JSON.readTree(solicitud.getRequestBody()); String modo = MODO.get();
        if (modo.equals("404") || modo.equals("403")) { responder(solicitud, Integer.parseInt(modo), "{}"); return; }
        boolean repetida = LIBERACIONES.containsKey(referencia);
        var respuesta = LIBERACIONES.computeIfAbsent(referencia, k -> JSON.valueToTree(Map.of("idReserva", referencia,
                "idDespacho", cuerpo.path("idDespacho").asText(), "estado", "LIBERADA", "motivoLiberacion", "CANCELACION", "liberadaEn", Instant.now().toString())));
        if (modo.equals("LENTO") && !repetida) try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        String contenido = JSON.writeValueAsString(respuesta);
        if (modo.equals("INVALIDA")) contenido = "{\"estado\":\"LIBERADA\"}";
        if (modo.equals("MOTIVO_AJENO")) contenido = contenido.replace("CANCELACION", "REASIGNACION");
        responder(solicitud, modo.equals("PERDIDA") && !repetida ? 503 : 200, contenido);
    }
    private static void responder(HttpExchange solicitud, int estado, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(java.nio.charset.StandardCharsets.UTF_8); solicitud.getResponseHeaders().set("Content-Type", "application/json");
        solicitud.sendResponseHeaders(estado, bytes.length); solicitud.getResponseBody().write(bytes); solicitud.close();
    }
}
