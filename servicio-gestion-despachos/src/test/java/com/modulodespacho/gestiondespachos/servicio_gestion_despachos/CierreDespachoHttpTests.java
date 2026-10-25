package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP de Gestión, Flyway y PostgreSQL reales; identidad y productores competidores controlados. */
@SpringBootTest(properties = {"SEGURIDAD_URL=http://127.0.0.1:1", "despacho.operacion.url=http://127.0.0.1:1",
        "despacho.programacion.recuperacion-habilitada=false", "despacho.recepcion.procesador-habilitado=false"})
@Import({TestcontainersConfiguration.class, CierreDespachoHttpTests.RelojPruebas.class})
@AutoConfigureMockMvc
class CierreDespachoHttpTests {
    static final Instant AHORA = Instant.parse("2026-10-25T03:30:00Z"); // Lima todavía es 24/10.
    static final String DESPACHO = id(101), INCIDENCIA = id(102), ACTOR = id(103), ZONA = id(104);
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired JsonMapper json;
    @Autowired Clock reloj;
    @Autowired RecepcionCentroJdbc recepciones;
    @Autowired ReprogramacionDespachoJdbc reprogramaciones;
    @Autowired CierreDespachoJdbc cierres;
    @Autowired HistorialIncidenciasJdbc historial;
    @Autowired DetalleIncidenciaJdbc detalle;
    @Autowired PlatformTransactionManager gestor;
    @Autowired PostgreSQLContainer postgres;
    @MockitoBean JwtDecoder decodificador;
    @TestConfiguration static class RelojPruebas {
        @Bean @Primary Clock relojReprogramacion() { return new RelojMutable(); }
    }
    static class RelojMutable extends Clock {
        final AtomicReference<Instant> ahora = new AtomicReference<>(AHORA);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zona) { return Clock.fixed(instant(), zona); }
        public Instant instant() { return ahora.get(); }
    }
    @BeforeEach void preparar() {
        reset(decodificador);
        ((RelojMutable) reloj).ahora.set(AHORA);
        sql.sql("TRUNCATE despachos CASCADE").update();
        sql.sql("""
                INSERT INTO parametros_configuracion(clave, valor, descripcion, actualizado_por)
                VALUES ('MAXIMO_INTENTOS','2','Máximo de pruebas','sistema')
                ON CONFLICT (clave) DO UPDATE SET valor='2'
                """).update();
        sql.sql("INSERT INTO zonas(id,nombre,estado) VALUES (CAST(:id AS uuid),'Zona reprogramación','INACTIVA') ON CONFLICT (id) DO NOTHING")
                .param("id", ZONA).update();
        sql.sql("""
                INSERT INTO despachos(id,pedido_id,zona_id,estado,codigo_operativo,codigo_rastreo_interno,version,numero_intentos,
                  destinatario_nombre,destinatario_telefono,direccion,distrito,peso_kg,volumen_m3,cantidad_paquetes,
                  fecha_programada,en_cola_desde,repartidor_id_externo,asignacion_diaria_id_externa,reserva_capacidad_id_externa,secuencia_ruta)
                VALUES (CAST(:id AS uuid),'PED-REPROGRAMACION',CAST(:zona AS uuid),'FALLIDO','DSP-REPROGRAMACION','TRK-REPROGRAMACION',8,1,
                  'Destinatario de prueba','900000000','Dirección de prueba','Lima',4.5,0.02,1,
                  DATE '2026-10-23',TIMESTAMPTZ '2026-10-23 12:00:00Z','repartidor-original','jornada-original','reserva-original',3)
                """).param("id", DESPACHO).param("zona", ZONA).update();
        sql.sql("""
                INSERT INTO intentos_entrega(id,despacho_id,resultado,motivo_codigo,consume_intento,comentario,
                  repartidor_id_externo,asignacion_diaria_id_externa,reserva_capacidad_id_externa,registrado_en)
                VALUES (CAST(:inc AS uuid),CAST(:des AS uuid),'FALLIDO','CLIENTE_AUSENTE',true,'Fallo original',
                  'repartidor-original','jornada-original','reserva-original',TIMESTAMPTZ '2026-10-24 15:00:00Z')
                """).param("inc", INCIDENCIA).param("des", DESPACHO).update();
        sql.sql("UPDATE despachos SET ultima_incidencia_id=CAST(:inc AS uuid) WHERE id::text=:id")
                .param("inc", INCIDENCIA).param("id", DESPACHO).update();
        sql.sql("""
                INSERT INTO historial_estados_despacho(despacho_id,tipo_operacion,estado_anterior,estado_nuevo,funcionalidad_origen,
                  ejecutor_tipo,ejecutor_id,origen,datos,registrado_en)
                VALUES (CAST(:id AS uuid),'TRANSICION','EN_CAMINO','FALLIDO','F-03','USUARIO',:actor,'MANUAL','{}',
                  TIMESTAMPTZ '2026-10-24 15:00:00Z')
                """).param("id", DESPACHO).param("actor", ACTOR).update();
        recepciones.confirmar(DESPACHO, new SolicitudRecepcion(INCIDENCIA, 8, false, "Recepción anterior"), ACTOR);
        when(decodificador.decode("gestor")).thenReturn(token(ACTOR,"acceso",List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("gestor2")).thenReturn(token(id(105),"acceso",List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("otro")).thenReturn(token(ACTOR,"acceso",List.of("OTRO")));
        when(decodificador.decode("servicio")).thenReturn(token(ACTOR,"servicio",List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("invalido")).thenThrow(new BadJwtException("No aceptado"));
    }

    @Test void cierraConservandoDatosRecepcionYLiberacionYProyectaSoloDatosPublicos() throws Exception {
        String antes = texto("SELECT (to_jsonb(d)-'estado'-'version'-'actualizado_en')::text FROM despachos d");
        String liberacion = texto("SELECT row_to_json(l)::text FROM liberaciones_capacidad l");
        enviar("gestor", cuerpo("  Decisión\noperativa  ", " Observación ")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.estado").value("DEVUELTO_A_ORIGEN"))
                .andExpect(jsonPath("$.versionDespacho").value(10)).andExpect(jsonPath("$.numeroIntentos").value(1))
                .andExpect(jsonPath("$.motivo").value("Decisión\noperativa")).andExpect(jsonPath("$.observacion").value("Observación"))
                .andExpect(jsonPath("$.cerradoEn").value(AHORA.toString())).andExpect(jsonPath("$.idEvento").isString());
        assertThat(texto("SELECT (to_jsonb(d)-'estado'-'version'-'actualizado_en')::text FROM despachos d")).isEqualTo(antes);
        assertThat(texto("SELECT row_to_json(l)::text FROM liberaciones_capacidad l")).isEqualTo(liberacion);
        assertThat(texto("SELECT observacion_recepcion FROM intentos_entrega")).isEqualTo("Recepción anterior");
        assertThat(texto("SELECT motivo_codigo FROM intentos_entrega")).isEqualTo("CLIENTE_AUSENTE");
        assertThat(numero("SELECT count(*) FROM historial_estados_despacho WHERE estado_nuevo='DEVUELTO_A_ORIGEN' AND motivo_codigo IS NULL AND jsonb_exists(datos,'controlCierre')")).isEqualTo(1);
        assertThat(texto("SELECT payload->'datos'->>'motivoCierre' FROM eventos_salientes")).isEqualTo("Decisión\noperativa");
        String detallePublico = http.perform(get("/api/v1/despachos/"+DESPACHO+"/incidencia").header("Authorization","Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("DEVUELTO_A_ORIGEN"))
                .andExpect(jsonPath("$.acciones.cerrarComoDevueltoAOrigen.habilitada").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(detallePublico).contains("motivoCierre").doesNotContain("controlCierre", "hashContenido", "respuestaOriginal");
        for (String ruta : List.of("/api/v1/despachos/fallidos", "/api/v1/despachos/pendientes"))
            http.perform(get(ruta).header("Authorization", "Bearer gestor")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(0));
    }
    @Test void cierrePermiteAnulacionLimiteSuperadoIntentosDisponiblesYNoIntentado() throws Exception {
      for (boolean simulado : List.of(false,true)) {
        for (int intentos : List.of(0, 1, 2, 3)) {
            preparar();
            sql.sql("UPDATE despachos SET numero_intentos=:n,pedido_anulado_en=now(),es_simulado=:simulado").param("n", intentos).param("simulado",simulado).update();
            if (intentos == 0) sql.sql("UPDATE intentos_entrega SET motivo_codigo='NO_INTENTADO',consume_intento=false").update();
            sql.sql("UPDATE liberaciones_capacidad SET requiere_revision=true").update();
            enviar("gestor", cuerpo("Decisión explícita", null)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.numeroIntentos").value(intentos))
                    .andExpect(simulado ? jsonPath("$.idEvento").value(org.hamcrest.Matchers.nullValue()) : jsonPath("$.idEvento").isString());
            assertThat(numero("SELECT count(*) FROM eventos_salientes")).isEqualTo(simulado ? 0 : 1);
            if (!simulado) {
                assertThat(texto("SELECT tipo_evento FROM eventos_salientes")).isEqualTo("DESPACHO_DEVUELTO_A_ORIGEN");
                assertThat(texto("SELECT payload->'datos'->>'motivoCierre' FROM eventos_salientes")).isEqualTo("Decisión explícita");
            }
            assertThat(numero("SELECT count(*) FROM historial_estados_despacho WHERE estado_nuevo='DEVUELTO_A_ORIGEN'")).isEqualTo(1);
        }
      }
    }
    @Test void exigeRecepcionActualYNoUnaHistorica() throws Exception {
        sql.sql("UPDATE intentos_entrega SET recibido_en_centro_en=NULL").update();
        enviar("gestor", cuerpo("Cierre", null)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_RECEPCION_PENDIENTE"));
        preparar();
        sql.sql("INSERT INTO intentos_entrega(id,despacho_id,resultado,motivo_codigo) VALUES(CAST(:inc AS uuid),CAST(:des AS uuid),'FALLIDO','NO_INTENTADO')")
                .param("inc", id(106)).param("des", DESPACHO).update();
        sql.sql("UPDATE despachos SET ultima_incidencia_id=CAST(:inc AS uuid) WHERE id::text=:des").param("inc", id(106)).param("des", DESPACHO).update();
        enviar("gestor", cuerpo("Cierre", null)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_DESPACHO_DESACTUALIZADO"));
        enviar("gestor", cuerpo("Cierre", null).replace(INCIDENCIA, id(106))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_RECEPCION_PENDIENTE"));
        sinDecision();
    }
    @Test void rechazaEstadoVersionYReferenciasInconsistentes() throws Exception {
        enviar("gestor", cuerpo("Cierre", null).replace("\"versionDespacho\":9", "\"versionDespacho\":8"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_DESPACHO_DESACTUALIZADO"));
        sql.sql("UPDATE despachos SET estado='ENTREGADO'").update();
        enviar("gestor", cuerpo("Cierre", null)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_ESTADO_INCOMPATIBLE"));
        sql.sql("UPDATE despachos SET estado='FALLIDO'").update();
        sql.sql("UPDATE intentos_entrega SET reserva_capacidad_id_externa='otra'").update();
        enviar("gestor", cuerpo("Cierre", null)).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_INCONSISTENTES"));
        sql.sql("DELETE FROM liberaciones_capacidad").update();
        enviar("gestor", cuerpo("Cierre", null)).andExpect(status().isInternalServerError());
        sinDecision();
    }
    @Test void recuperaOriginalTrasRecrearServicioYTranscurrir24HorasOCambiosTecnicos() throws Exception {
        ((RelojMutable) reloj).ahora.set(AHORA.plusNanos(123456789));
        String original = enviar("gestor", cuerpo("\u00a0Motivo\nlibre\uFEFF", "\u3000 ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cerradoEn").value("2026-10-25T03:30:00.123456Z")).andReturn().getResponse().getContentAsString();
        ((RelojMutable) reloj).ahora.set(AHORA.plus(Duration.ofDays(3)));
        sql.sql("UPDATE eventos_salientes SET estado_envio='ENVIO_FALLIDO',intentos_envio=6").update();
        sql.sql("UPDATE liberaciones_capacidad SET requiere_revision=true").update();
        var recreado = new CierreDespachoJdbc(sql, json, reloj, historial, gestor);
        assertThat(json.writeValueAsString(recreado.cerrar(DESPACHO, new SolicitudCierre(INCIDENCIA, 9, "Motivo\nlibre", null), ACTOR))).isEqualTo(original);
        enviar("gestor", cuerpo("Motivo\nlibre", null)).andExpect(status().isOk()).andExpect(content().json(original));
        enviar("gestor2", cuerpo("Motivo\nlibre", null)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_OPERACION_REUTILIZADA"));
        enviar("gestor", cuerpo("Otro", null)).andExpect(status().isConflict());
        enviar("gestor", cuerpo("Motivo\nlibre", "Otra")).andExpect(status().isConflict());
        enviar("gestor", cuerpo("Motivo\nlibre", null).replace("\"versionDespacho\":9", "\"versionDespacho\":10"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_ESTADO_INCOMPATIBLE"));
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(10);
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isEqualTo(1);
    }
    @Test void noFabricaResultadoDesdeMetadatosIncompletos() throws Exception {
        enviar("gestor", cuerpo("Cierre", null)).andExpect(status().isOk());
        String datos = texto("SELECT datos::text FROM historial_estados_despacho WHERE estado_nuevo='DEVUELTO_A_ORIGEN'");
        for (String campo : List.of("observacion", "numeroIntentos", "idEvento")) {
            sql.sql("UPDATE historial_estados_despacho SET datos=CAST(:datos AS jsonb) #- CAST(:ruta AS text[]) WHERE estado_nuevo='DEVUELTO_A_ORIGEN'")
                    .param("datos",datos).param("ruta","{controlCierre,respuestaOriginal,"+campo+"}").update();
            enviar("gestor", cuerpo("Cierre", null)).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_INCONSISTENTES"));
        }
        sql.sql("UPDATE historial_estados_despacho SET datos=jsonb_set(datos,'{controlCierre,respuestaOriginal}','{}') WHERE estado_nuevo='DEVUELTO_A_ORIGEN'").update();
        enviar("gestor", cuerpo("Cierre", null)).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_INCONSISTENTES"));
    }
    @Test void autenticaAntesDeExistenciaYFormatoYValidaContrato() throws Exception {
        http.perform(post(ruta()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        enviar("invalido", "{}").andExpect(status().isUnauthorized());
        enviar("otro", "{}").andExpect(status().isForbidden());
        enviar("servicio", "{}").andExpect(status().isForbidden());
        http.perform(post("/api/v1/despachos/inexistente/devolucion-origen").header("Authorization","Bearer gestor")
                .contentType("application/json").content(cuerpo("Cierre",null))).andExpect(status().isNotFound());
        http.perform(post(ruta()+"?extra=1").header("Authorization","Bearer gestor").contentType("application/json").content(cuerpo("Cierre",null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_CIERRE_INVALIDOS"));
        for (String invalido : List.of("{}", "[]", "{", cuerpo(" ",null), cuerpo("Cierre",null).replace("}",",\"extra\":true}")))
            enviar("gestor",invalido).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_CIERRE_INVALIDOS"));
        sinDecision();
    }
    @Test void rollbackAnteCadaEscrituraYUnicidadEnLaBase() throws Exception {
        for (String tabla : List.of("despachos", "historial_estados_despacho", "eventos_salientes")) {
            sql.sql("CREATE FUNCTION fallo_cierre() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fallo inyectado'; END $$").update();
            sql.sql("CREATE TRIGGER fallo_cierre BEFORE "+(tabla.equals("despachos")?"UPDATE":"INSERT")+" ON "+tabla+" FOR EACH ROW EXECUTE FUNCTION fallo_cierre()").update();
            try {
                enviar("gestor", cuerpo("Cierre",null)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DESP_ERROR_CIERRE_NO_DISPONIBLE"));
                sinDecision(); assertThat(numero("SELECT count(*) FROM historial_estados_despacho")).isEqualTo(2);
            } finally { sql.sql("DROP TRIGGER fallo_cierre ON "+tabla).update(); sql.sql("DROP FUNCTION fallo_cierre()").update(); }
        }
        enviar("gestor", cuerpo("Cierre",null)).andExpect(status().isOk());
        assertThatThrownBy(() -> new TransactionTemplate(gestor).execute(s -> historial.registrar(DESPACHO,"TRANSICION","FALLIDO","DEVUELTO_A_ORIGEN",ACTOR,null,Map.of(),AHORA)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
    @Test void duplicadoConcurrenteRecuperaElMismoCuerpo() throws Exception { carrera("gestor", "Cierre", true); }
    @Test void cierresDistintosConcurrentesSoloAceptanUno() throws Exception { carrera("gestor", "Otro", false); }
    @Test void gestoresConcurrentesSoloAceptanUno() throws Exception { carrera("gestor2", "Cierre", false); }
    @Test void compiteConReprogramacionRealYAnulacionPorLaVersionCanonica() throws Exception {
        for (boolean anulacion : List.of(false,true)) {
            preparar();
            try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
                var inicio = new CountDownLatch(1);
                var uno = hilos.submit(() -> { inicio.await(); return enviar("gestor",cuerpo("Cierre",null)).andReturn().getResponse().getStatus(); });
                var dos = hilos.submit(() -> { inicio.await();
                    if (anulacion) return sql.sql("UPDATE despachos SET pedido_anulado_en=now(),version=version+1 WHERE id::text=:id AND version=9").param("id",DESPACHO).update()==1?200:409;
                    return http.perform(post("/api/v1/despachos/"+DESPACHO+"/reprogramacion").header("Authorization","Bearer gestor").contentType("application/json")
                            .content("{\"idIncidencia\":\""+INCIDENCIA+"\",\"versionDespacho\":9,\"nuevaFechaProgramada\":\"2026-10-25\"}"))
                            .andReturn().getResponse().getStatus();
                });
                inicio.countDown();
                assertThat(List.of(uno.get(15,TimeUnit.SECONDS),dos.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
                assertThat(numero("SELECT version FROM despachos")).isEqualTo(10);
            }
        }
    }
    @Test void liberacionPendienteContinuaDespuesDelCierreSinCambiarSuVersion() throws Exception {
        enviar("gestor", cuerpo("Cierre",null)).andExpect(status().isOk());
        var puerto = org.mockito.Mockito.mock(LiberadorCapacidad.class);
        when(puerto.liberar(any())).thenReturn(new LiberadorCapacidad.Resultado(true,false,null,AHORA));
        var pendientes = new LiberacionesCapacidadJdbc(sql);
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(new ProcesadorLiberaciones(pendientes,puerto,reloj,hilos,List.of(Duration.ofSeconds(30))).procesarUna()).isTrue();
        }
        assertThat(texto("SELECT estado FROM liberaciones_capacidad")).isEqualTo("CONFIRMADA");
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(10);
    }
    @Test void eventoReintentaPorHttpConElMismoPayloadSinRevertirLaDecision() throws Exception {
        // Receptor HTTP controlado: acredita transporte/outbox, no la instalación real de Ventas.
        var servidor = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var cuerpos = Collections.synchronizedList(new ArrayList<String>());
        var codigo = new java.util.concurrent.atomic.AtomicInteger(503);
        servidor.createContext("/api/v1/integraciones/despachos/eventos", intercambio -> {
            cuerpos.add(new String(intercambio.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            intercambio.sendResponseHeaders(codigo.get(), -1); intercambio.close();
        });
        servidor.start();
        try {
            String original = enviar("gestor", cuerpo("Decisión operativa", null)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var cliente = org.springframework.web.client.RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + servidor.getAddress().getPort()).build();
            var entrega = new com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure.EntregaEventosProgramacion(
                    sql, json, cliente, () -> {
                        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        return "credencial-sintetica";
                    }, cliente, () -> "no-se-usa");
            for (int intento = 0; intento < 6; intento++) {
                sql.sql("UPDATE eventos_salientes SET proximo_intento_en=now()").update();
                entrega.ejecutar();
                assertThat(texto("SELECT estado_envio FROM eventos_salientes")).isEqualTo(intento == 5 ? "ENVIO_FALLIDO" : "PENDIENTE");
                assertThat(texto("SELECT estado FROM despachos")).isEqualTo("DEVUELTO_A_ORIGEN");
            }
            enviar("gestor", cuerpo("Decisión operativa", null)).andExpect(status().isOk()).andExpect(content().json(original));
            // Reenvío del mismo evento controlado: no crea otra decisión.
            sql.sql("UPDATE eventos_salientes SET estado_envio='PENDIENTE'").update();
            codigo.set(204);
            sql.sql("UPDATE eventos_salientes SET proximo_intento_en=now()").update();
            entrega.ejecutar();
            assertThat(texto("SELECT estado_envio FROM eventos_salientes")).isEqualTo("ENVIADO");
            assertThat(numero("SELECT intentos_envio FROM eventos_salientes")).isEqualTo(7);
            assertThat(cuerpos).hasSize(7).allMatch(cuerpo -> cuerpo.equals(cuerpos.getFirst()));
            var evento = json.readTree(cuerpos.getFirst());
            assertThat(evento.path("tipo").asString()).isEqualTo("DESPACHO_DEVUELTO_A_ORIGEN");
            assertThat(evento.path("idEvento").asString()).isEqualTo(json.readTree(original).path("idEvento").asString());
            assertThat(evento.path("datos").path("motivoCierre").asString()).isEqualTo("Decisión operativa");
            enviar("gestor", cuerpo("Decisión operativa", null)).andExpect(status().isOk()).andExpect(content().json(original));
            assertThat(numero("SELECT count(*) FROM eventos_salientes")).isEqualTo(1);
        } finally { servidor.stop(0); }
    }
    @Test void migraV3ConCierreHistoricoSinInventarResultadoYRechazaDuplicados() throws Exception {
        String base = "cierre_"+UUID.randomUUID().toString().replace("-","");
        sql.sql("CREATE DATABASE "+base).update();
        String url = "jdbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/"+base;
        Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).target(MigrationVersion.fromVersion("3")).load().migrate();
        try (var conexion = java.sql.DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword()); var sentencia = conexion.createStatement()) {
            sentencia.execute("INSERT INTO despachos(id,pedido_id,estado,codigo_operativo) VALUES ('"+DESPACHO+"','PED-PREVIO','DEVUELTO_A_ORIGEN','DSP-PREVIO')");
            sentencia.execute("INSERT INTO historial_estados_despacho(despacho_id,tipo_operacion,estado_anterior,estado_nuevo,funcionalidad_origen,ejecutor_tipo,origen,datos) VALUES ('"+DESPACHO+"','TRANSICION','FALLIDO','DEVUELTO_A_ORIGEN','F-04','SISTEMA','MANUAL','{}')");
        }
        var migracion = Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).load();
        migracion.migrate(); assertThat(migracion.info().current().getVersion().toString()).isEqualTo("4");
        try (var conexion = java.sql.DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword()); var sentencia = conexion.createStatement()) {
            var filas = sentencia.executeQuery("SELECT datos::text FROM historial_estados_despacho");
            assertThat(filas.next()).isTrue(); assertThat(filas.getString(1)).isEqualTo("{}");
            assertThatThrownBy(() -> sentencia.execute("INSERT INTO historial_estados_despacho(despacho_id,tipo_operacion,estado_anterior,estado_nuevo,funcionalidad_origen,ejecutor_tipo,origen,datos) SELECT despacho_id,tipo_operacion,estado_anterior,estado_nuevo,funcionalidad_origen,ejecutor_tipo,origen,datos FROM historial_estados_despacho"))
                    .isInstanceOf(java.sql.SQLException.class).satisfies(error -> assertThat(((java.sql.SQLException)error).getSQLState()).isEqualTo("23505"));
        }
    }
    private void carrera(String actor, String motivo, boolean duplicado) throws Exception {
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            var inicio = new CountDownLatch(1);
            var uno = hilos.submit(() -> { inicio.await(); return enviar("gestor",cuerpo("Cierre",null)).andReturn().getResponse(); });
            var dos = hilos.submit(() -> { inicio.await(); return enviar(actor,cuerpo(motivo,null)).andReturn().getResponse(); });
            inicio.countDown(); var a = uno.get(15,TimeUnit.SECONDS); var b = dos.get(15,TimeUnit.SECONDS);
            assertThat(List.of(a.getStatus(),b.getStatus())).containsExactlyInAnyOrderElementsOf(duplicado?List.of(200,200):List.of(200,409));
            if (duplicado) assertThat(json.readTree(a.getContentAsString())).isEqualTo(json.readTree(b.getContentAsString()));
        }
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(10);
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isEqualTo(1);
        assertThat(numero("SELECT count(*) FROM historial_estados_despacho WHERE estado_nuevo='DEVUELTO_A_ORIGEN'")).isEqualTo(1);
    }
    private void sinDecision() {
        assertThat(numero("SELECT count(*) FROM historial_estados_despacho WHERE estado_nuevo='DEVUELTO_A_ORIGEN'")).isZero();
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isZero();
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(9);
    }
    private String cuerpo(String motivo,String observacion) {
        var datos = new LinkedHashMap<String,Object>(); datos.put("idIncidencia",INCIDENCIA); datos.put("versionDespacho",9);
        datos.put("motivo",motivo); datos.put("observacion",observacion); return json.writeValueAsString(datos);
    }
    private ResultActions enviar(String token,String cuerpo) throws Exception {
        return http.perform(post(ruta()).header("Authorization","Bearer "+token).contentType("application/json").content(cuerpo));
    }
    private String ruta() { return "/api/v1/despachos/"+DESPACHO+"/devolucion-origen"; }
    private int numero(String consulta) { return sql.sql(consulta).query(Integer.class).single(); }
    private String texto(String consulta) { return sql.sql(consulta).query(String.class).single(); }
    private static String id(long valor) { return new UUID(0,valor).toString(); }
    private Jwt token(String actor,String tipo,List<String> roles) {
        return Jwt.withTokenValue("prueba").header("alg","RS256").subject(actor).claim("tipo",tipo).claim("roles",roles)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }
}
