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
@Import({TestcontainersConfiguration.class, ReprogramacionDespachoHttpTests.RelojPruebas.class})
@AutoConfigureMockMvc
class ReprogramacionDespachoHttpTests {
    static final Instant AHORA = Instant.parse("2026-10-25T03:30:00Z"); // Lima todavía es 24/10.
    static final String DESPACHO = id(101), INCIDENCIA = id(102), ACTOR = id(103), ZONA = id(104);
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired JsonMapper json;
    @Autowired Clock reloj;
    @Autowired RecepcionCentroJdbc recepciones;
    @Autowired ReprogramacionDespachoJdbc reprogramaciones;
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
    @Test void replayConservaInstanteConRelojNanosegundoYEjecutorUuidEnMayusculas() throws Exception {
        ((RelojMutable) reloj).ahora.set(AHORA.plusNanos(123456789));
        String ejecutor = "abcdefaa-0000-4000-8000-000000000001";
        when(decodificador.decode("gestor2")).thenReturn(token(ejecutor.toUpperCase(Locale.ROOT), "acceso", List.of("GESTOR_DESPACHO")));
        String original = enviar("gestor2", cuerpo("2026-10-25", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reprogramadoEn").value("2026-10-25T03:30:00.123456Z"))
                .andReturn().getResponse().getContentAsString();
        enviar("gestor2", cuerpo("2026-10-25", null)).andExpect(status().isOk()).andExpect(content().json(original));
        when(decodificador.decode("gestor2")).thenReturn(token(ejecutor, "acceso", List.of("GESTOR_DESPACHO")));
        enviar("gestor2", cuerpo("2026-10-25", null)).andExpect(status().isOk()).andExpect(content().json(original));
        assertThat(numero("SELECT count(*) FROM reprogramaciones_despacho")).isEqualTo(1);
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isEqualTo(1);
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
            String original = enviar("gestor", cuerpo("2026-10-25", null)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var cliente = org.springframework.web.client.RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + servidor.getAddress().getPort()).build();
            var entrega = new com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure.EntregaEventosProgramacion(
                    sql, json, cliente, () -> {
                        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        return "credencial-sintetica";
                    }, cliente, () -> "no-se-usa");
            for (int intento = 0; intento < 2; intento++) {
                sql.sql("UPDATE eventos_salientes SET proximo_intento_en=now()").update();
                entrega.ejecutar();
                assertThat(texto("SELECT estado_envio FROM eventos_salientes")).isEqualTo("PENDIENTE");
                assertThat(texto("SELECT estado FROM despachos")).isEqualTo("PENDIENTE_ASIGNACION");
            }
            codigo.set(204);
            sql.sql("UPDATE eventos_salientes SET proximo_intento_en=now()").update();
            entrega.ejecutar();
            assertThat(texto("SELECT estado_envio FROM eventos_salientes")).isEqualTo("ENVIADO");
            assertThat(numero("SELECT intentos_envio FROM eventos_salientes")).isEqualTo(3);
            assertThat(cuerpos).hasSize(3).allMatch(cuerpo -> cuerpo.equals(cuerpos.getFirst()));
            var evento = json.readTree(cuerpos.getFirst());
            assertThat(evento.path("tipo").asString()).isEqualTo("DESPACHO_REPROGRAMADO");
            assertThat(evento.path("idEvento").asString()).isEqualTo(json.readTree(original).path("idEvento").asString());
            assertThat(evento.path("datos").path("nuevaFechaProgramada").asString()).isEqualTo("2026-10-25");
            enviar("gestor", cuerpo("2026-10-25", null)).andExpect(status().isOk()).andExpect(content().json(original));
            assertThat(numero("SELECT count(*) FROM eventos_salientes")).isEqualTo(1);
        } finally { servidor.stop(0); }
    }
    @Test void reprogramaSinConsumirIntentoYConservaHistoricosAunqueLaLiberacionEstePendiente() throws Exception {
        String liberacion = texto("SELECT row_to_json(l)::text FROM liberaciones_capacidad l");
        enviar("gestor", cuerpo("2026-10-25","  Disponible\nmañana  ")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.estado").value("PENDIENTE_ASIGNACION"))
                .andExpect(jsonPath("$.versionDespacho").value(10)).andExpect(jsonPath("$.numeroIntentos").value(1))
                .andExpect(jsonPath("$.fechaProgramada").value("2026-10-25"))
                .andExpect(jsonPath("$.reprogramadoEn").value(AHORA.toString()))
                .andExpect(jsonPath("$.observacion").value("Disponible\nmañana")).andExpect(jsonPath("$.idEvento").isString());
        assertThat(texto("SELECT row_to_json(l)::text FROM liberaciones_capacidad l")).isEqualTo(liberacion);
        assertThat(texto("SELECT observacion_recepcion FROM intentos_entrega")).isEqualTo("Recepción anterior");
        assertThat(texto("SELECT zona_id::text FROM despachos")).isEqualTo(ZONA);
        assertThat(texto("SELECT direccion FROM despachos")).isEqualTo("Dirección de prueba");
        assertThat(numero("SELECT count(*) FROM despachos WHERE repartidor_id_externo IS NULL AND asignacion_diaria_id_externa IS NULL AND reserva_capacidad_id_externa IS NULL AND secuencia_ruta IS NULL")).isEqualTo(1);
        assertThat(numero("SELECT count(*) FROM historial_estados_despacho WHERE tipo_operacion='TRANSICION' AND funcionalidad_origen='F-04' AND datos->>'nuevaFechaProgramada'='2026-10-25'")).isEqualTo(1);
        assertThat(numero("SELECT count(*) FROM reprogramaciones_despacho r JOIN historial_estados_despacho h ON h.id=r.historial_id JOIN despachos d ON d.id=r.despacho_id WHERE r.creado_en=h.registrado_en AND r.creado_en=d.en_cola_desde")).isEqualTo(1);
        assertThat(texto("SELECT payload->'datos'->>'nuevaFechaProgramada' FROM eventos_salientes")).isEqualTo("2026-10-25");
        assertThat(detalle.consultar(DESPACHO).acciones().reprogramar().habilitada()).isFalse();
        http.perform(get("/api/v1/despachos/pendientes?tipoIntento=REINTENTOS").header("Authorization","Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contenido[0].idDespacho").value(DESPACHO))
                .andExpect(jsonPath("$.contenido[0].reintento").value(true))
                .andExpect(jsonPath("$.contenido[0].asignable").value(false));
        http.perform(get("/api/v1/despachos/fallidos").header("Authorization","Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(0));
        http.perform(post("/api/v1/despachos/"+DESPACHO+"/asignacion").header("Authorization","Bearer gestor")
                .contentType("application/json").content("{\"idRepartidor\":\"nuevo\",\"idJornada\":\"otra\",\"observacion\":null}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_FECHA_PROGRAMADA_FUTURA"));
    }
    @Test void noIntentadoConCeroIntentosYSimuladoNoPublicaEvento() throws Exception {
        sql.sql("UPDATE despachos SET numero_intentos=0,es_simulado=true").update();
        sql.sql("UPDATE intentos_entrega SET motivo_codigo='NO_INTENTADO',consume_intento=false").update();
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isOk()).andExpect(jsonPath("$.numeroIntentos").value(0))
                .andExpect(jsonPath("$.idEvento").value(org.hamcrest.Matchers.nullValue()));
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isZero();
        assertThat(numero("SELECT count(*) FROM reprogramaciones_despacho")).isEqualTo(1);
    }
    @Test void usaMaximoVigenteYNoUnDosFijo() throws Exception {
        sql.sql("UPDATE parametros_configuracion SET valor='1' WHERE clave='MAXIMO_INTENTOS'").update();
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_MAXIMO_INTENTOS_ALCANZADO"));
        sql.sql("UPDATE despachos SET numero_intentos=3").update();
        sql.sql("UPDATE parametros_configuracion SET valor='4' WHERE clave='MAXIMO_INTENTOS'").update();
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isOk()).andExpect(jsonPath("$.numeroIntentos").value(3));
    }
    @Test void rechazaPoliticaInvalidaSinInventarMaximo() throws Exception {
        for(String valor:List.of("0","-1","abc","2.5","2147483648")) {
            sql.sql("UPDATE parametros_configuracion SET valor=:valor WHERE clave='MAXIMO_INTENTOS'").param("valor",valor).update();
            enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_INCONSISTENTES"));
        }
        sql.sql("DELETE FROM parametros_configuracion WHERE clave='MAXIMO_INTENTOS'").update();
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isInternalServerError());
        sinDecision();
    }
    @Test void exigeRecepcionAntesDeAnulacionYLimiteYAnulacionAntesDelLimite() throws Exception {
        sql.sql("UPDATE despachos SET numero_intentos=2,pedido_anulado_en=now()").update();
        sql.sql("UPDATE intentos_entrega SET recibido_en_centro_en=NULL").update();
        enviar("gestor",cuerpo("2026-10-24",null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_RECEPCION_PENDIENTE"));
        sql.sql("UPDATE intentos_entrega SET recibido_en_centro_en=now()").update();
        enviar("gestor",cuerpo("2026-10-24",null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_PEDIDO_ANULADO"));
        sinDecision();
    }
    @Test void otraRecepcionHistoricaNoHabilitaLaIncidenciaVigente() throws Exception {
        nuevaIncidencia();
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DESPACHO_DESACTUALIZADO"));
        var actual = new SolicitudReprogramacion(id(106),11,LocalDate.parse("2026-10-25"),null);
        enviar("gestor",json.writeValueAsString(actual)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_RECEPCION_PENDIENTE"));
        assertThat(numero("SELECT count(*) FROM reprogramaciones_despacho")).isZero();
    }
    @Test void rechazaEstadoVersionYReferenciasIncompatiblesSinEscrituras() throws Exception {
        enviar("gestor",cuerpo("2026-10-25",null).replace("\"versionDespacho\":9","\"versionDespacho\":8"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_DESPACHO_DESACTUALIZADO"));
        sql.sql("UPDATE despachos SET estado='ENTREGADO'").update();
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_ESTADO_INCOMPATIBLE"));
        sql.sql("UPDATE despachos SET estado='FALLIDO'").update();
        sql.sql("UPDATE intentos_entrega SET reserva_capacidad_id_externa='reserva-equivocada'").update();
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isInternalServerError());
        sql.sql("DELETE FROM liberaciones_capacidad").update();
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isInternalServerError());
        sinDecision();
    }
    @Test void validaHoyEnLimaDentroDeLaOperacionYCambiaAlCruzarMedianoche() throws Exception {
        enviar("gestor",cuerpo("2026-10-24",null)).andExpect(status().isBadRequest());
        ((RelojMutable)reloj).ahora.set(Instant.parse("2026-10-25T05:00:00Z"));
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("nuevaFechaProgramada"));
        enviar("gestor",cuerpo("2026-10-26",null)).andExpect(status().isOk());
    }
    @Test void recuperaElOriginalTrasMedianocheRecreacionYNuevaSalida() throws Exception {
        String original=enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ((RelojMutable)reloj).ahora.set(Instant.parse("2026-11-01T12:00:00Z"));
        nuevaIncidencia();
        sql.sql("UPDATE parametros_configuracion SET valor='invalida' WHERE clave='MAXIMO_INTENTOS'").update();
        var recreado=new ReprogramacionDespachoJdbc(sql,json,reloj,historial,gestor);
        assertThat(json.writeValueAsString(recreado.reprogramar(DESPACHO,new SolicitudReprogramacion(INCIDENCIA,9,LocalDate.parse("2026-10-25"),null),ACTOR)))
                .isEqualTo(original);
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isOk()).andExpect(content().json(original));
        assertThat(numero("SELECT count(*) FROM reprogramaciones_despacho")).isEqualTo(1);
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isEqualTo(1);
        assertThat(texto("SELECT ultima_incidencia_id::text FROM despachos")).isEqualTo(id(106));
    }
    @Test void identidadReutilizadaRechazaOtroActorOContenidoYNormalizaTexto() throws Exception {
        String original=enviar("gestor",cuerpo("2026-10-25"," \u00a0Texto\ncon salto\uFEFF ")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        enviar("gestor",cuerpo("2026-10-25","Texto\ncon salto")).andExpect(status().isOk()).andExpect(content().json(original));
        for(String cuerpo:List.of(cuerpo("2026-10-26","Texto\ncon salto"),cuerpo("2026-10-25","Otro"))) {
            enviar("gestor",cuerpo).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_OPERACION_REUTILIZADA"));
        }
        enviar("gestor2",cuerpo("2026-10-25","Texto\ncon salto")).andExpect(status().isConflict());
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isEqualTo(1);
    }
    @Test void noFabricaUnExitoDesdeUnaRespuestaOriginalIncompleta() throws Exception {
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isOk());
        sql.sql("UPDATE reprogramaciones_despacho SET respuesta_original='{}'").update();
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_INCONSISTENTES"));
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(10);
    }
    @Test void autenticaAntesDeExistenciaYFormatoYRechazaParametros() throws Exception {
        http.perform(post(ruta()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        enviar("invalido","{}").andExpect(status().isUnauthorized());
        enviar("otro","{}").andExpect(status().isForbidden());
        enviar("servicio","{}").andExpect(status().isForbidden());
        http.perform(post("/api/v1/despachos/inexistente/reprogramacion").header("Authorization","Bearer gestor")
                .contentType("application/json").content(cuerpo("2026-10-25",null))).andExpect(status().isNotFound());
        http.perform(post(ruta()+"?extra=1").header("Authorization","Bearer gestor").contentType("application/json")
                .content(cuerpo("2026-10-25",null))).andExpect(status().isBadRequest());
        enviar("gestor",cuerpo("2026-10-25",null).replace("}",",\"extra\":true}")).andExpect(status().isBadRequest());
        sinDecision();
    }
    @Test void revierteTodoAnteFalloEnCadaEscritura() throws Exception {
        for(String tabla:List.of("despachos","historial_estados_despacho","eventos_salientes","reprogramaciones_despacho")) {
            sql.sql("CREATE OR REPLACE FUNCTION fallo_reprogramacion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fallo inyectado'; END $$").update();
            sql.sql("CREATE TRIGGER fallo_reprogramacion BEFORE "+(tabla.equals("despachos")?"UPDATE":"INSERT")+" ON "+tabla+" FOR EACH ROW EXECUTE FUNCTION fallo_reprogramacion()").update();
            try {
                enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("DESP_ERROR_REPROGRAMACION_NO_DISPONIBLE"));
                sinDecision();
                assertThat(numero("SELECT count(*) FROM historial_estados_despacho")).isEqualTo(2);
                assertThat(texto("SELECT reserva_capacidad_id_externa FROM despachos")).isEqualTo("reserva-original");
            } finally { sql.sql("DROP TRIGGER fallo_reprogramacion ON "+tabla).update(); sql.sql("DROP FUNCTION fallo_reprogramacion()").update(); }
        }
        enviar("gestor",cuerpo("2026-10-25",null)).andExpect(status().isOk());
    }
    @Test void duplicadoExactoConcurrenteDevuelveUnUnicoResultado() throws Exception { carrera("gestor",false,List.of(200,200)); }
    @Test void otroContenidoConcurrenteNoGeneraDosDecisiones() throws Exception { carrera("gestor",true,List.of(200,409)); }
    @Test void otroActorConcurrenteNoSobrescribeLaDecision() throws Exception { carrera("gestor2",false,List.of(200,409)); }
    @Test void compitePorVersionConProductoresDeCierreYAnulacionControlados() throws Exception {
        for(boolean cierre:List.of(false,true)) {
            preparar();
            try(var hilos=Executors.newVirtualThreadPerTaskExecutor()) {
                var inicio=new CountDownLatch(1);
                var uno=hilos.submit(()->{inicio.await();return enviar("gestor",cuerpo("2026-10-25",null)).andReturn().getResponse().getStatus();});
                var dos=hilos.submit(()->{inicio.await();int filas=sql.sql(cierre
                        ? "UPDATE despachos SET estado='DEVUELTO_A_ORIGEN',version=version+1 WHERE id::text=:id AND version=9"
                        : "UPDATE despachos SET pedido_anulado_en=now(),version=version+1 WHERE id::text=:id AND version=9")
                        .param("id",DESPACHO).update();return filas==1?200:409;});
                inicio.countDown();
                assertThat(List.of(uno.get(15,TimeUnit.SECONDS),dos.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
                assertThat(numero("SELECT version FROM despachos")).isEqualTo(10);
            }
        }
    }
    @Test void historialComunExigeTransaccionYParticipaEnSuRollback() {
        assertThatThrownBy(()->historial.registrar(DESPACHO,"TRANSICION","FALLIDO","PENDIENTE_ASIGNACION",ACTOR,null,Map.of(),AHORA))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new TransactionTemplate(gestor).execute(s->{
            historial.registrar(DESPACHO,"TRANSICION","FALLIDO","PENDIENTE_ASIGNACION",ACTOR,null,Map.of(),AHORA);
            throw new IllegalStateException("Rollback del productor");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(numero("SELECT count(*) FROM historial_estados_despacho")).isEqualTo(2);
    }
    @Test void migraV2ConDatosPreviosSinReescribirNiInventarResultados() throws Exception {
        String base="reprogramacion_"+UUID.randomUUID().toString().replace("-","");
        sql.sql("CREATE DATABASE "+base).update(); // Solo dentro del contenedor desechable de esta suite.
        String url="jdbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/"+base;
        Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).target(MigrationVersion.fromVersion("2")).load().migrate();
        try(var conexion=java.sql.DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword());var sentencia=conexion.createStatement()) {
            sentencia.execute("INSERT INTO despachos(pedido_id,estado,codigo_operativo) VALUES ('PED-PREVIO','FALLIDO','DSP-PREVIO')");
        }
        var migracion=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).load();
        migracion.migrate();
        assertThat(migracion.info().current().getVersion().toString()).isEqualTo("7");
        try(var conexion=java.sql.DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword());var sentencia=conexion.createStatement()) {
            var resultado=sentencia.executeQuery("SELECT (SELECT count(*) FROM despachos WHERE pedido_id='PED-PREVIO'),(SELECT count(*) FROM reprogramaciones_despacho)");
            assertThat(resultado.next()).isTrue(); assertThat(resultado.getInt(1)).isEqualTo(1); assertThat(resultado.getInt(2)).isZero();
        }
    }
    private void carrera(String segundoActor,boolean distinto,List<Integer> esperados) throws Exception {
        try(var hilos=Executors.newVirtualThreadPerTaskExecutor()) {
            var inicio=new CountDownLatch(1);
            var uno=hilos.submit(()->{inicio.await();return enviar("gestor",cuerpo("2026-10-25",null)).andReturn().getResponse().getStatus();});
            var dos=hilos.submit(()->{inicio.await();return enviar(segundoActor,cuerpo(distinto?"2026-10-26":"2026-10-25",null)).andReturn().getResponse().getStatus();});
            inicio.countDown();
            assertThat(List.of(uno.get(15,TimeUnit.SECONDS),dos.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrderElementsOf(esperados);
        }
        assertThat(numero("SELECT count(*) FROM reprogramaciones_despacho")).isEqualTo(1);
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isEqualTo(1);
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(10);
    }
    private void nuevaIncidencia() {
        sql.sql("""
                INSERT INTO intentos_entrega(id,despacho_id,resultado,motivo_codigo,repartidor_id_externo,asignacion_diaria_id_externa,reserva_capacidad_id_externa)
                VALUES(CAST(:inc AS uuid),CAST(:des AS uuid),'FALLIDO','CLIENTE_AUSENTE','repartidor-nuevo','jornada-nueva','reserva-nueva')
                """).param("inc",id(106)).param("des",DESPACHO).update();
        sql.sql("UPDATE despachos SET estado='FALLIDO',ultima_incidencia_id=CAST(:inc AS uuid),version=11 WHERE id::text=:id")
                .param("inc",id(106)).param("id",DESPACHO).update();
    }
    private void sinDecision() {
        assertThat(numero("SELECT count(*) FROM reprogramaciones_despacho")).isZero();
        assertThat(numero("SELECT count(*) FROM eventos_salientes")).isZero();
        assertThat(numero("SELECT version FROM despachos")).isEqualTo(9);
    }
    private String cuerpo(String fecha,String observacion) {
        var datos=new LinkedHashMap<String,Object>();datos.put("idIncidencia",INCIDENCIA);datos.put("versionDespacho",9);
        datos.put("nuevaFechaProgramada",fecha);datos.put("observacion",observacion);return json.writeValueAsString(datos);
    }
    private ResultActions enviar(String token,String cuerpo) throws Exception {
        return http.perform(post(ruta()).header("Authorization","Bearer "+token).contentType("application/json").content(cuerpo));
    }
    private String ruta(){return "/api/v1/despachos/"+DESPACHO+"/reprogramacion";}
    private int numero(String consulta){return sql.sql(consulta).query(Integer.class).single();}
    private String texto(String consulta){return sql.sql(consulta).query(String.class).single();}
    private static String id(long valor){return new UUID(0,valor).toString();}
    private Jwt token(String actor,String tipo,List<String> roles) {
        return Jwt.withTokenValue("prueba").header("alg","RS256").subject(actor).claim("tipo",tipo).claim("roles",roles)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }
}
