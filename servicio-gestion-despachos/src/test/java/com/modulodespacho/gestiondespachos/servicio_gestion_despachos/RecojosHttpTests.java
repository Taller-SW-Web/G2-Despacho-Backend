package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure.CapacidadProgramacionHttp;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.application.GestorRecojos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** ES-F02-09, ES-F03-09 y ES-F04-07 con PostgreSQL y Flyway reales; Operación y el JWT se identifican como dobles. */
@Import({TestcontainersConfiguration.class, RecojosHttpTests.RelojPruebas.class})
@SpringBootTest(properties = {"despacho.seguridad.url=http://127.0.0.1:1", "despacho.operacion.url=http://127.0.0.1:1", "despacho.operacion.token-servicio=tecnico"})
@AutoConfigureMockMvc
class RecojosHttpTests {
    static final Instant AHORA = Instant.parse("2026-10-17T15:41:00Z");
    static final String REPARTIDOR = "7d0f4a52-1b39-4c0a-9a0e-2f5e1b6c8d11";
    static final String JORNADA = "d42d8d4b-1a80-481f-9f35-9ff0c6c935f1";
    static final String RESERVA = "f3a1d2c4-5b6e-4f70-8a91-b2c3d4e5f607";
    static final String GESTOR = "11111111-1111-4111-8111-111111111111";
    @TestConfiguration(proxyBeanMethods = false) static class RelojPruebas {
        @Bean @Primary Clock relojRecojos() { return Clock.fixed(AHORA, ZoneOffset.UTC); }
    }
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired JsonMapper json;
    @Autowired GestorRecojos gestor;
    @MockitoBean JwtDecoder decodificador;
    @MockitoBean CapacidadProgramacionHttp capacidad;
    UUID despacho;

    @BeforeEach void preparar() {
        sql.sql("TRUNCATE despachos, claves_idempotencia CASCADE").update();
        when(decodificador.decode("gestor")).thenReturn(token("acceso", GESTOR, List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("repartidor")).thenReturn(token("acceso", UUID.randomUUID().toString(), List.of()));
        when(decodificador.decode("operacion")).thenReturn(token("servicio", "modulo-despacho", List.of()));
        when(capacidad.reservarRecojo(any(), any(), any(), anyString(), anyString(), any(), any(), anyInt())).thenReturn(RESERVA);
        despacho = despacho("ENTREGADO", true);
    }

    @Test void generaUnRecojoDePruebaSobreUnDespachoSimuladoEntregadoSinTocarlo() throws Exception {
        http.perform(get("/api/v1/recojos/elegibles").header("Authorization", "Bearer gestor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.despachos[0].idDespacho").value(despacho.toString()));
        generar(despacho).andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("PENDIENTE_ASIGNACION"))
                .andExpect(jsonPath("$.esSimulado").value(true)).andExpect(jsonPath("$.destinatario").value("Ana Mendoza"))
                .andExpect(jsonPath("$.lineas[0].cantidad").value(1)).andExpect(jsonPath("$.cantidadPaquetes").value(1));
        assertThat(sql.sql("SELECT estado FROM despachos").query(String.class).single()).isEqualTo("ENTREGADO");
        assertThat(contar("SELECT count(*) FROM eventos_salientes")).isZero();
        http.perform(get("/api/v1/recojos/elegibles").header("Authorization", "Bearer gestor")).andExpect(jsonPath("$.despachos.length()").value(0));
        http.perform(get("/api/v1/recojos").header("Authorization", "Bearer gestor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido.length()").value(1)).andExpect(jsonPath("$.totalElementos").value(1));
    }

    @Test void rechazaUnRecojoNoPermitido() throws Exception {
        generar(despacho("EN_CAMINO", true)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_ESTADO_INCOMPATIBLE"));
        generar(despacho("ENTREGADO", false)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_RECOJO_SOLO_SIMULADO"));
        generar(despacho).andExpect(status().isCreated());
        generar(despacho).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_RECOJO_ACTIVO"));
        generar(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test void soloElGestorOperaYSoloElModuloTransiciona() throws Exception {
        http.perform(get("/api/v1/recojos")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/recojos").header("Authorization", "Bearer repartidor")).andExpect(status().isForbidden());
        transicion("gestor", UUID.randomUUID(), UUID.randomUUID(), Map.of()).andExpect(status().isForbidden());
    }

    @Test void asignaConReservaYPosicionYLaMismaClaveNoDuplica() throws Exception {
        UUID recojo = recojo(); UUID clave = UUID.randomUUID();
        String original = asignar(recojo, clave, 1).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ASIGNADO"))
                .andExpect(jsonPath("$.secuenciaRuta").value(1)).andExpect(jsonPath("$.idJornada").value(JORNADA)).andExpect(jsonPath("$.version").value(2))
                .andReturn().getResponse().getContentAsString();
        asignar(recojo, clave, 1).andExpect(status().isOk()).andExpect(content().json(original));
        verify(capacidad, times(1)).reservarRecojo(eq(clave), eq(recojo), eq(despacho), eq(REPARTIDOR), eq(JORNADA), any(), any(), eq(1));
        assertThat(contar("SELECT count(*) FROM historial_estados_recojo WHERE estado_nuevo='ASIGNADO'")).isEqualTo(1);
        JsonNode proyeccion = json.readTree(sql.sql("SELECT payload::text FROM eventos_salientes WHERE tipo_evento='PROYECCION_RECOJO'").query(String.class).single());
        assertThat(proyeccion.path("idRecojo").asString()).isEqualTo(recojo.toString());
        assertThat(proyeccion.path("idReserva").asString()).isEqualTo(RESERVA);
        assertThat(proyeccion.path("hitos").path("asignadoEn").asString()).isEqualTo("2026-10-17T15:41:00Z");
        assertThat(proyeccion.path("lineas").get(0).path("cantidad").asInt()).isEqualTo(1);
    }

    @Test void unaJornadaDeOtroTipoOSinCapacidadDejaElRecojoEnLaCola() throws Exception {
        UUID recojo = recojo();
        when(capacidad.reservarRecojo(any(), any(), any(), anyString(), anyString(), any(), any(), anyInt()))
                .thenThrow(ErrorDeNegocio.de(409, "DESP_ERROR_TIPO_JORNADA_INCOMPATIBLE", "La jornada no admite este tipo de trabajo"));
        asignar(recojo, UUID.randomUUID(), 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_TIPO_JORNADA_INCOMPATIBLE"));
        reset(capacidad);
        when(capacidad.reservarRecojo(any(), any(), any(), anyString(), anyString(), any(), any(), anyInt()))
                .thenThrow(ErrorDeNegocio.de(422, "DESP_ERROR_CAPACIDAD_EXCEDIDA", "Capacidad de carga del repartidor excedida"));
        asignar(recojo, UUID.randomUUID(), 1).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("DESP_ERROR_CAPACIDAD_EXCEDIDA"));
        assertThat(sql.sql("SELECT estado FROM recojos").query(String.class).single()).isEqualTo("PENDIENTE_ASIGNACION");
        assertThat(contar("SELECT count(*) FROM eventos_salientes")).isZero();
    }

    @Test void unaCaidaDeOperacionSeRecuperaConLaMismaClave() throws Exception {
        UUID recojo = recojo(); UUID clave = UUID.randomUUID();
        when(capacidad.reservarRecojo(any(), any(), any(), anyString(), anyString(), any(), any(), anyInt()))
                .thenThrow(ErrorDeNegocio.de(503, "DESP_ERROR_OPERACION_NO_DISPONIBLE", "No se pudo confirmar la operación de capacidad"));
        asignar(recojo, clave, 1).andExpect(status().isServiceUnavailable());
        assertThat(sql.sql("SELECT estado FROM recojos").query(String.class).single()).isEqualTo("PENDIENTE_ASIGNACION");
        reset(capacidad);
        when(capacidad.reservarRecojo(any(), any(), any(), anyString(), anyString(), any(), any(), anyInt())).thenReturn(RESERVA);
        sql.sql("UPDATE asignaciones_recojo SET proximo_intento_en = now() - interval '1 second'").update();
        gestor.recuperarPendientes();
        assertThat(sql.sql("SELECT estado FROM recojos").query(String.class).single()).isEqualTo("ASIGNADO");
        verify(capacidad).reservarRecojo(eq(clave), any(), any(), anyString(), anyString(), any(), any(), anyInt());
    }

    @Test void unaFechaFuturaOVersionObsoletaNoSeAsigna() throws Exception {
        UUID recojo = recojo();
        asignar(recojo, UUID.randomUUID(), 9).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_RECOJO_DESACTUALIZADO"));
        sql.sql("UPDATE recojos SET fecha_programada = '2026-10-20'").update();
        asignar(recojo, UUID.randomUUID(), 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_FECHA_PROGRAMADA_FUTURA"));
        http.perform(post("/api/v1/recojos/" + recojo + "/asignacion").header("Authorization", "Bearer gestor").header("If-Match", "1")
                .contentType("application/json").content(json.writeValueAsString(Map.of("idRepartidor", REPARTIDOR, "idJornada", JORNADA)))).andExpect(status().isBadRequest());
        verifyNoInteractions(capacidad);
    }

    @Test void elRecorridoCompletoRegistraHistorialYSoloLiberaAlRecibir() throws Exception {
        UUID recojo = asignado();
        UUID evidencia = UUID.randomUUID(), clave = UUID.randomUUID();
        transicion("operacion", recojo, UUID.randomUUID(), cuerpo("EN_CAMINO", null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_CAMINO")).andExpect(jsonPath("$.hitos.enCaminoEn").exists());
        Map<String, Object> recogido = cuerpo("RECOGIDO", evidencia, null);
        String original = transicion("operacion", recojo, clave, recogido).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("RECOGIDO"))
                .andExpect(jsonPath("$.hitos.recogidoEn").value("2026-10-17T15:41:00Z")).andExpect(jsonPath("$.numeroIntentos").value(0))
                .andReturn().getResponse().getContentAsString();
        transicion("operacion", recojo, clave, recogido).andExpect(status().isOk()).andExpect(content().json(original));
        assertThat(contar("SELECT count(*) FROM historial_estados_recojo WHERE estado_nuevo='RECOGIDO'")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM eventos_salientes WHERE destino='OPERACION_LIBERACION'")).isZero();
        assertThat(sql.sql("SELECT estado FROM despachos").query(String.class).single()).isEqualTo("ENTREGADO");

        int version = version(recojo);
        accion(recojo, "recepcion", UUID.randomUUID(), version + 1, Map.of()).andExpect(status().isConflict());
        UUID recepcion = UUID.randomUUID();
        accion(recojo, "recepcion", recepcion, version, Map.of("observacion", "Sin daños")).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("RECIBIDO_EN_CENTRO"));
        accion(recojo, "recepcion", recepcion, version, Map.of("observacion", "Sin daños")).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("RECIBIDO_EN_CENTRO"));
        JsonNode liberacion = json.readTree(sql.sql("SELECT payload::text FROM eventos_salientes WHERE destino='OPERACION_LIBERACION'").query(String.class).single());
        assertThat(liberacion.path("motivo").asString()).isEqualTo("RECEPCION_EN_CENTRO");
        assertThat(liberacion.path("idDespacho").asString()).isEqualTo(despacho.toString());
        assertThat(contar("SELECT count(*) FROM eventos_salientes WHERE destino='VENTAS'")).isZero();
        http.perform(get("/api/v1/recojos/" + recojo).header("Authorization", "Bearer gestor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.historial.length()").value(5)).andExpect(jsonPath("$.historial[4].observaciones").value("Sin daños"));
    }

    @Test void unRecojoNoRealizadoLiberaCapacidadYElGestorReprogramaOCierra() throws Exception {
        UUID recojo = asignado();
        transicion("operacion", recojo, UUID.randomUUID(), cuerpo("EN_CAMINO", null, null)).andExpect(status().isOk());
        transicion("operacion", recojo, UUID.randomUUID(), cuerpo("FALLIDO", UUID.randomUUID(), "CLIENTE_NO_ENTREGA_PRODUCTO")).andExpect(status().isOk())
                .andExpect(jsonPath("$.numeroIntentos").value(1)).andExpect(jsonPath("$.motivoFallo").value("CLIENTE_NO_ENTREGA_PRODUCTO"));
        JsonNode liberacion = json.readTree(sql.sql("SELECT payload::text FROM eventos_salientes WHERE destino='OPERACION_LIBERACION'").query(String.class).single());
        assertThat(liberacion.path("motivo").asString()).isEqualTo("RECOJO_NO_REALIZADO");
        accion(recojo, "reprogramacion", UUID.randomUUID(), version(recojo), Map.of("fechaProgramada", "2026-10-16")).andExpect(status().isBadRequest());
        accion(recojo, "reprogramacion", UUID.randomUUID(), version(recojo), Map.of("fechaProgramada", "2026-10-17")).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE_ASIGNACION")).andExpect(jsonPath("$.idJornada").doesNotExist()).andExpect(jsonPath("$.numeroIntentos").value(1));
        asignar(recojo, UUID.randomUUID(), version(recojo)).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ASIGNADO")).andExpect(jsonPath("$.motivoFallo").doesNotExist());
        transicion("operacion", recojo, UUID.randomUUID(), cuerpo("FALLIDO", null, "NO_INTENTADO")).andExpect(status().isOk()).andExpect(jsonPath("$.numeroIntentos").value(1));
        accion(recojo, "cierre", UUID.randomUUID(), version(recojo), Map.of()).andExpect(status().isBadRequest());
        accion(recojo, "cierre", UUID.randomUUID(), version(recojo), Map.of("motivoCierre", "Cliente desiste")).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CERRADO_SIN_RECOJO"));
        accion(recojo, "cierre", UUID.randomUUID(), version(recojo), Map.of("motivoCierre", "Otra vez")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_ESTADO_INCOMPATIBLE"));
    }

    @Test void validaMotivoEvidenciaOrigenYPropiedadDeLaTransicion() throws Exception {
        UUID recojo = asignado();
        transicion("operacion", recojo, UUID.randomUUID(), cuerpo("RECOGIDO", UUID.randomUUID(), null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_ESTADO_INCOMPATIBLE"));
        transicion("operacion", recojo, UUID.randomUUID(), cuerpo("EN_CAMINO", null, null)).andExpect(status().isOk());
        transicion("operacion", recojo, UUID.randomUUID(), cuerpo("RECOGIDO", null, null)).andExpect(status().isBadRequest());
        transicion("operacion", recojo, UUID.randomUUID(), cuerpo("FALLIDO", UUID.randomUUID(), "PAQUETE_DANADO")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_MOTIVO_INVALIDO"));
        Map<String, Object> ajena = new HashMap<>(cuerpo("RECOGIDO", UUID.randomUUID(), null));
        ajena.put("idJornada", UUID.randomUUID().toString());
        transicion("operacion", recojo, UUID.randomUUID(), ajena).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_RECOJO_REASIGNADO"));
        transicion("operacion", UUID.randomUUID(), UUID.randomUUID(), cuerpo("EN_CAMINO", null, null)).andExpect(status().isNotFound());
    }

    @Test void unRecojoRealInformaAVentasYElMotivoDeRecojoNoAplicaAUnaEntrega() throws Exception {
        UUID recojo = asignado();
        sql.sql("UPDATE recojos SET es_simulado = false").update();
        transicion("operacion", recojo, UUID.randomUUID(), cuerpo("EN_CAMINO", null, null)).andExpect(status().isOk());
        transicion("operacion", recojo, UUID.randomUUID(), cuerpo("RECOGIDO", UUID.randomUUID(), null)).andExpect(status().isOk());
        JsonNode evento = json.readTree(sql.sql("SELECT payload::text FROM eventos_salientes WHERE destino='VENTAS'").query(String.class).single());
        assertThat(evento.path("tipo").asString()).isEqualTo("RECOJO_RECOGIDO");
        assertThat(evento.path("datos").path("idRecojo").asString()).isEqualTo(recojo.toString());
        assertThat(sql.sql("SELECT consume_intento FROM motivos_fallo WHERE codigo='PRODUCTO_NO_CORRESPONDE'").query(Boolean.class).single()).isTrue();
        http.perform(get("/internal/v1/catalogos/motivos-fallo").header("Authorization", "Bearer operacion")).andExpect(status().isOk())
                .andExpect(jsonPath("$.motivos[?(@.codigo=='PRODUCTO_NO_CORRESPONDE')].ambito").value("RECOJO"))
                .andExpect(jsonPath("$.motivos[?(@.codigo=='CLIENTE_AUSENTE')].ambito").value("AMBOS"));
    }

    private UUID asignado() throws Exception {
        UUID recojo = recojo();
        asignar(recojo, UUID.randomUUID(), 1).andExpect(status().isOk());
        return recojo;
    }
    private UUID recojo() throws Exception {
        return UUID.fromString(json.readTree(generar(despacho).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("idRecojo").asString());
    }
    private int version(UUID recojo) {
        return sql.sql("SELECT version FROM recojos WHERE id=:id").param("id", recojo).query(Integer.class).single();
    }
    private long contar(String consulta) { return sql.sql(consulta).query(Long.class).single(); }
    private Map<String, Object> cuerpo(String destino, UUID evidencia, String motivo) {
        Map<String, Object> datos = new HashMap<>(Map.of("estadoDestino", destino, "idRepartidor", REPARTIDOR, "idJornada", JORNADA, "ejecutor", GESTOR, "origen", "MANUAL"));
        if (evidencia != null) datos.put("idEvidencia", evidencia);
        if (motivo != null) datos.put("motivo", motivo);
        return datos;
    }
    private ResultActions generar(UUID idDespacho) throws Exception {
        return http.perform(post("/api/v1/despachos/" + idDespacho + "/recojos/simulacion").header("Authorization", "Bearer gestor"));
    }
    private ResultActions asignar(UUID recojo, UUID clave, int version) throws Exception {
        return accion(recojo, "asignacion", clave, version, Map.of("idRepartidor", REPARTIDOR, "idJornada", JORNADA));
    }
    private ResultActions accion(UUID recojo, String accion, UUID clave, int version, Map<String, Object> cuerpo) throws Exception {
        return http.perform(post("/api/v1/recojos/" + recojo + "/" + accion).header("Authorization", "Bearer gestor").header("Idempotency-Key", clave)
                .header("If-Match", version).contentType("application/json").content(json.writeValueAsString(cuerpo)));
    }
    private ResultActions transicion(String token, UUID recojo, UUID clave, Map<String, Object> cuerpo) throws Exception {
        return http.perform(post("/internal/v1/recojos/" + recojo + "/transiciones").header("Authorization", "Bearer " + token).header("Idempotency-Key", clave)
                .contentType("application/json").content(json.writeValueAsString(cuerpo)));
    }
    private UUID despacho(String estado, boolean simulado) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                INSERT INTO despachos(id, pedido_id, codigo_operativo, codigo_rastreo_interno, estado, es_simulado, destinatario_nombre,
                    destinatario_telefono, direccion, referencia, distrito, peso_kg, volumen_m3, cantidad_paquetes, fecha_programada, numero_intentos, version)
                VALUES (:id, :pedido, :operativo, :rastreo, :estado, :simulado, 'Ana Mendoza', '+51999000111', 'Av. Ejemplo 123', 'Portón azul', 'Lince',
                    :peso, 0.02, 1, '2026-10-10', 0, 3)
                """).param("id", id).param("pedido", "PED-" + id).param("operativo", "D-" + id.toString().substring(0, 8)).param("rastreo", "TRK-" + id.toString().substring(0, 8))
                .param("estado", estado).param("simulado", simulado).param("peso", new BigDecimal("2.5")).update();
        return id;
    }
    private Jwt token(String tipo, String sujeto, List<String> roles) {
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject(sujeto).claim("tipo", tipo).claim("roles", roles).claim("scope", "").build();
    }
}
