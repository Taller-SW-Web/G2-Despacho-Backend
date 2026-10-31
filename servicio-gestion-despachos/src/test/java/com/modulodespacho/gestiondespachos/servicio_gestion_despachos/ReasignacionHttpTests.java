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

/** PostgreSQL y HTTP reales; F-06 y el decodificador de identidad son dobles explícitos. */
@SpringBootTest(properties={"SEGURIDAD_URL=http://127.0.0.1:1","despacho.programacion.recuperacion-habilitada=false"})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ReasignacionHttpTests {
    static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    static final HttpServer SERVIDOR=servidor();
    static final Map<String,JsonNode> TRANSFERENCIAS=new ConcurrentHashMap<>();
    static final List<String> CLAVES=new CopyOnWriteArrayList<>();
    static final AtomicReference<String> MODO=new AtomicReference<>("OK");
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired ReasignarDespacho reasignar;
    @Autowired AlmacenReasignaciones almacen;
    @Autowired PuertoTransferencia capacidad;
    @MockitoBean JwtDecoder identidad;
    @MockitoBean LectorDatosOperativos operacion;
    final UUID despacho=UUID.randomUUID(),repOrigen=UUID.randomUUID(),jornadaOrigen=UUID.randomUUID(),reserva=UUID.randomUUID();
    final UUID repDestino=UUID.randomUUID(),jornadaDestino=UUID.randomUUID();
    final UUID fijo=UUID.randomUUID(),otro=UUID.randomUUID();

    @DynamicPropertySource static void configurar(DynamicPropertyRegistry p) {
        p.add("despacho.operacion.url",() -> "http://127.0.0.1:"+SERVIDOR.getAddress().getPort());
        p.add("despacho.operacion.token-servicio",() -> "capacidad-prueba");
    }
    @AfterAll static void detener() { SERVIDOR.stop(0); }
    @BeforeEach void preparar() {
        sql.sql("DROP TRIGGER IF EXISTS fallo_reasignacion ON historial_estados_despacho").update();
        sql.sql("DROP TRIGGER IF EXISTS fallo_reasignacion ON eventos_salientes").update();
        sql.sql("DROP TRIGGER IF EXISTS fallo_reasignacion ON reasignaciones_despacho").update();
        sql.sql("TRUNCATE despachos CASCADE").update();
        TRANSFERENCIAS.clear(); CLAVES.clear(); MODO.set("OK");
        when(identidad.decode("gestor")).thenReturn(token("acceso","gestor-reasignacion",List.of("GESTOR_DESPACHO"),""));
        when(identidad.decode("otro")).thenReturn(token("acceso","otro",List.of(),""));
        when(identidad.decode("servicio")).thenReturn(token("servicio","modulo-despacho",List.of(),"gestion:despachos:transicionar"));
        insertar(despacho,repOrigen,jornadaOrigen,1,"ASIGNADO");
        insertar(fijo,repOrigen,jornadaOrigen,2,"EN_CAMINO");
        insertar(otro,repOrigen,jornadaOrigen,3,"ASIGNADO");
        insertar(UUID.randomUUID(),repDestino,jornadaDestino,1,"ASIGNADO");
        when(operacion.consultar(any(),any())).thenReturn(new DatosOperativos(Map.of(),Map.of(jornadaOrigen.toString(),
                new DatosOperativos.Jornada(jornadaOrigen.toString(),DatosOperativos.EstadoJornada.ACTIVA,null,Instant.now()))));
    }
    @Test void confirmaTransferenciaAuditoriaProyeccionYRespuestaOriginalSinDuplicar() throws Exception {
        String clave=UUID.randomUUID().toString(), cuerpo=cuerpo();
        String original=enviar(clave,cuerpo).andExpect(status().isOk()).andExpect(jsonPath("$.idRepartidor").value(repDestino.toString()))
                .andExpect(jsonPath("$.posicionSecuencia").value(2)).andExpect(jsonPath("$.version").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(texto("SELECT estado FROM reasignaciones_despacho")).isEqualTo("CONFIRMADA");
        assertThat(numero("SELECT count(*) FROM historial_estados_despacho WHERE tipo_operacion='REASIGNACION'")).isEqualTo(1);
        assertThat(numero("SELECT count(*) FROM eventos_salientes WHERE destino='OPERACION'")).isEqualTo(1);
        assertThat(numero("SELECT count(*) FROM eventos_salientes WHERE destino='VENTAS'")).isZero();
        assertThat(numero("SELECT count(*) FROM despachos WHERE asignacion_diaria_id_externa='"+jornadaOrigen+"' AND secuencia_ruta=1")).isZero();
        sql.sql("UPDATE despachos SET estado='EN_CAMINO',version=version+1 WHERE id=:id").param("id",despacho).update();
        enviar(clave,cuerpo).andExpect(status().isOk()).andExpect(content().json(original));
        assertThat(CLAVES).hasSize(1);
        enviar(clave,cuerpo.replace("Redistribución","Otro motivo")).andExpect(status().isConflict());
    }
    @Test void respuestaRemotaPerdidaConservaCercoYRecuperaSinUnaSegundaTransferencia() throws Exception {
        MODO.set("RESPUESTA_PERDIDA");
        String clave=UUID.randomUUID().toString();
        enviar(clave,cuerpo()).andExpect(status().isServiceUnavailable());
        assertThat(TRANSFERENCIAS).hasSize(1);
        assertThat(texto("SELECT estado FROM reasignaciones_despacho")).isEqualTo("PENDIENTE");
        String ruta="/api/v1/jornadas/"+jornadaOrigen+"/ruta";
        String version=http.perform(get(ruta).header("Authorization","Bearer gestor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.despachos[0].reasignacionEnProceso").value(true)).andReturn().getResponse().getHeader("ETag");
        http.perform(put("/api/v1/jornadas/"+jornadaOrigen+"/secuencia").header("Authorization","Bearer gestor")
                .header("If-Match",version).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("despachos",List.of(Map.of("idDespacho",despacho,"posicion",3),
                        Map.of("idDespacho",fijo,"posicion",2),Map.of("idDespacho",otro,"posicion",1))))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_REASIGNACION_EN_PROCESO"));
        traslado().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_REASIGNACION_EN_PROCESO"));
        assertThat(numero("SELECT version FROM despachos WHERE id='"+despacho+"'")).isEqualTo(1);
        enviar(clave,cuerpo()).andExpect(status().isOk());
        assertThat(CLAVES).hasSize(2).allMatch(c -> c.equals(CLAVES.getFirst()));
        assertThat(TRANSFERENCIAS).hasSize(1);
    }
    @Test void falloLocalDespuesDeTransferirSeRecuperaHaciaAdelanteSinLiberarNiRecrear() throws Exception {
        for(String tabla:List.of("historial_estados_despacho","eventos_salientes","reasignaciones_despacho")) {
            preparar();
            sql.sql("CREATE OR REPLACE FUNCTION fallo_reasignacion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fallo inducido'; END $$").update();
            sql.sql("CREATE TRIGGER fallo_reasignacion BEFORE "+(tabla.equals("reasignaciones_despacho")?"UPDATE":"INSERT")+" ON "+tabla
                    +" FOR EACH ROW "+(tabla.equals("reasignaciones_despacho")?"WHEN (NEW.estado='CONFIRMADA') ":"")+"EXECUTE FUNCTION fallo_reasignacion()").update();
            String clave=UUID.randomUUID().toString();
            enviar(clave,cuerpo()).andExpect(status().isServiceUnavailable());
            assertThat(texto("SELECT reserva_capacidad_id_externa FROM despachos WHERE id='"+despacho+"'")).isEqualTo(reserva.toString());
            assertThat(numero("SELECT count(*) FROM eventos_salientes")).isZero();
            assertThat(numero("SELECT count(*) FROM historial_estados_despacho")).isZero();
            assertThat(TRANSFERENCIAS).hasSize(1);
            traslado().andExpect(status().isConflict());
            sql.sql("DROP TRIGGER fallo_reasignacion ON "+tabla).update();
            enviar(clave,cuerpo()).andExpect(status().isOk());
            assertThat(TRANSFERENCIAS).hasSize(1);
        }
    }
    @Test void nuevaInstanciaRecuperaIntencionPersistidaTrasVencerElArrendamiento() throws Exception {
        MODO.set("RESPUESTA_PERDIDA");
        enviar(UUID.randomUUID().toString(),cuerpo()).andExpect(status().isServiceUnavailable());
        sql.sql("UPDATE reasignaciones_despacho SET procesando_hasta=now()-interval '1 second',proximo_intento_en=now()-interval '1 second'").update();
        new ReasignarDespacho(almacen,capacidad,Clock.systemUTC()).recuperarPendientes();
        assertThat(texto("SELECT estado FROM reasignaciones_despacho")).isEqualTo("CONFIRMADA");
        assertThat(CLAVES).hasSize(2).allMatch(c -> c.equals(CLAVES.getFirst()));
        assertThat(TRANSFERENCIAS).hasSize(1);
    }
    @Test void timeoutORespuestaInvalidaNoRetiranCercoNiGeneranOtraTransferencia() throws Exception {
        for(String modo:List.of("LENTO","INVALIDA")) {
            preparar(); MODO.set(modo); String clave=UUID.randomUUID().toString();
            enviar(clave,cuerpo()).andExpect(status().isServiceUnavailable());
            assertThat(texto("SELECT estado FROM reasignaciones_despacho")).isEqualTo("PENDIENTE");
            traslado().andExpect(status().isConflict());
            MODO.set("OK");
            enviar(clave,cuerpo()).andExpect(status().isOk());
            assertThat(TRANSFERENCIAS).hasSize(1);
        }
    }
    @Test void rechazoDefinitivoSeRecuerdaYRetiraCercoSinCambiarOrigen() throws Exception {
        for(String modo:List.of("409","422")) {
            preparar(); MODO.set(modo); String clave=UUID.randomUUID().toString();
            enviar(clave,cuerpo()).andExpect(status().is(Integer.parseInt(modo)));
            enviar(clave,cuerpo()).andExpect(status().is(Integer.parseInt(modo)));
            assertThat(texto("SELECT estado FROM reasignaciones_despacho")).isEqualTo("RECHAZADA");
            assertThat(TRANSFERENCIAS).isEmpty(); assertThat(CLAVES).hasSize(1);
            traslado().andExpect(status().isOk());
        }
    }
    @Test void dosGestoresNoTransfierenDosVecesLaMismaVersion() throws Exception {
        try(var hilos=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> tarea=() -> enviar(UUID.randomUUID().toString(),cuerpo()).andReturn().getResponse().getStatus();
            var a=hilos.submit(tarea);var b=hilos.submit(tarea);
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(TRANSFERENCIAS).hasSize(1);
    }
    @Test void inicioDeTrasladoGanadorRechazaReasignacionAntesDeInvocarF06() throws Exception {
        traslado().andExpect(status().isOk());
        enviar(UUID.randomUUID().toString(),cuerpo()).andExpect(status().isConflict());
        assertThat(CLAVES).isEmpty();
        for(String estado:List.of("EN_CAMINO","ENTREGADO","FALLIDO","CANCELADO","DEVUELTO_A_ORIGEN")) {
            sql.sql("UPDATE despachos SET estado=:estado,version=1 WHERE id=:id").param("id",despacho).param("estado",estado).update();
            enviar(UUID.randomUUID().toString(),cuerpo()).andExpect(status().isConflict());
        }
        assertThat(CLAVES).isEmpty();
    }
    @Test void validaMotivoMismoDestinoVersionYPermisosAntesDeTransferir() throws Exception {
        for(String valor:List.of("", "   ")) enviar(UUID.randomUUID().toString(),cuerpo().replace("Redistribución",valor)).andExpect(status().isBadRequest());
        enviar(UUID.randomUUID().toString(),cuerpo().replace(repDestino.toString(),repOrigen.toString())).andExpect(status().isBadRequest());
        enviar(UUID.randomUUID().toString(),cuerpo().replace("}",",\"extra\":true}")).andExpect(status().isBadRequest());
        for(String credencial:List.of("otro","servicio")) http.perform(post(ruta()).header("Authorization","Bearer "+credencial)
                .contentType("application/json").content(cuerpo())).andExpect(status().isForbidden());
        http.perform(post(ruta()).contentType("application/json").content(cuerpo())).andExpect(status().isUnauthorized());
        http.perform(post(ruta()).header("Authorization","Bearer gestor").contentType("application/json").content(cuerpo())).andExpect(status().isBadRequest());
        assertThat(CLAVES).isEmpty();
    }
    private String ruta() { return "/api/v1/despachos/"+despacho+"/reasignacion"; }
    private String cuerpo() { return JSON.writeValueAsString(Map.of("idNuevoRepartidor",repDestino,"idNuevaJornada",jornadaDestino,"motivo","Redistribución")); }
    private ResultActions enviar(String clave,String cuerpo) throws Exception {
        return http.perform(post(ruta()).header("Authorization","Bearer gestor").header("Idempotency-Key",clave).header("If-Match","\"1\"").contentType("application/json").content(cuerpo));
    }
    private ResultActions traslado() throws Exception {
        return http.perform(post("/internal/v1/despachos/"+despacho+"/transiciones").header("Authorization","Bearer servicio").header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content(JSON.writeValueAsString(Map.of("estadoDestino","EN_CAMINO","idRepartidor",repOrigen,"idJornada",jornadaOrigen,"ejecutor",repOrigen,"origen","MANUAL"))));
    }
    private void insertar(UUID id,UUID repartidor,UUID jornada,int posicion,String estado) {
        sql.sql("""
                INSERT INTO despachos(id,pedido_id,codigo_operativo,codigo_rastreo_interno,es_simulado,estado,destinatario_nombre,
                  destinatario_telefono,direccion,distrito,peso_kg,volumen_m3,cantidad_paquetes,fecha_programada,
                  repartidor_id_externo,asignacion_diaria_id_externa,reserva_capacidad_id_externa,secuencia_ruta)
                VALUES (:id,:pedido,:pedido,:pedido,true,:estado,'Prueba','999111222','Dirección sintética','Lima',2,0.01,1,
                  current_date,:repartidor,:jornada,:reserva,:posicion)
                """).param("id",id).param("pedido",id.toString()).param("estado",estado).param("repartidor",repartidor.toString())
                .param("jornada",jornada.toString()).param("reserva",reserva.toString()).param("posicion",posicion).update();
    }
    private long numero(String consulta) { return sql.sql(consulta).query(Long.class).single(); }
    private String texto(String consulta) { return sql.sql(consulta).query(String.class).single(); }
    private Jwt token(String tipo,String sub,List<String> roles,String scope) { return Jwt.withTokenValue(tipo).header("alg","RS256").subject(sub)
            .claim("tipo",tipo).claim("roles",roles).claim("scope",scope).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build(); }
    private static HttpServer servidor() {
        try {
            HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            s.createContext("/internal/v1/capacidad/transferencias",ReasignacionHttpTests::transferir);s.start();return s;
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    private static void transferir(HttpExchange solicitud) throws IOException {
        if (!"Bearer capacidad-prueba".equals(solicitud.getRequestHeaders().getFirst("Authorization"))) { responder(solicitud,403,"{}");return; }
        String clave=solicitud.getRequestHeaders().getFirst("Idempotency-Key");CLAVES.add(clave);
        var cuerpo=JSON.readTree(solicitud.getRequestBody());
        String modo=MODO.get();
        if (modo.equals("409") || modo.equals("422")) { responder(solicitud,Integer.parseInt(modo),"{}");return; }
        boolean repetida=TRANSFERENCIAS.containsKey(clave);
        JsonNode respuesta=TRANSFERENCIAS.computeIfAbsent(clave,k -> JSON.valueToTree(Map.of("idReservaOrigen",cuerpo.path("idReservaOrigen").asText(),
                "idDespacho",cuerpo.path("idDespacho").asText(),"idNuevaReserva",UUID.randomUUID().toString(),"idNuevaJornada",cuerpo.path("idNuevaJornada").asText(),
                "estado","TRANSFERIDA","capacidadRemanente",Map.of("pesoKg",98,"volumenM3",0.98,"paquetes",9),"transferidaEn",Instant.now().toString())));
        if (modo.equals("LENTO")&&!repetida) try { Thread.sleep(2000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        responder(solicitud,modo.equals("RESPUESTA_PERDIDA")&&!repetida?503:200,modo.equals("INVALIDA")?"{\"estado\":\"TRANSFERIDA\",\"capacidadRemanente\":{\"pesoKg\":1,\"volumenM3\":1}}":JSON.writeValueAsString(respuesta));
    }
    private static void responder(HttpExchange s,int estado,String cuerpo) throws IOException {
        byte[] bytes=cuerpo.getBytes(java.nio.charset.StandardCharsets.UTF_8);s.getResponseHeaders().set("Content-Type","application/json");
        s.sendResponseHeaders(estado,bytes.length);s.getResponseBody().write(bytes);s.close();
    }
}
