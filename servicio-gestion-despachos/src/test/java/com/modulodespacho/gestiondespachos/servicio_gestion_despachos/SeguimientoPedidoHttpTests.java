package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL desechable y Flyway reales; el decodificador de identidad es un doble explícito. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"SEGURIDAD_URL=http://127.0.0.1:1", "spring.flyway.enabled=true"})
@AutoConfigureMockMvc
class SeguimientoPedidoHttpTests {
    private static final String PEDIDO = "PED-000abc";
    private static final UUID DESPACHO = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @MockitoBean JwtDecoder decodificador;

    @BeforeEach void preparar() {
        sql.sql("TRUNCATE despachos CASCADE").update();
        when(decodificador.decode("canal")).thenReturn(Jwt.withTokenValue("canal").header("alg", "RS256")
                .subject("modulo-chatbot").claim("tipo", "servicio").claim("scope", "seguimientos:leer")
                .audience(List.of("api-despacho")).expiresAt(Instant.now().plusSeconds(300)).build());
        sql.sql("""
                INSERT INTO despachos(id, pedido_id, estado, codigo_rastreo_interno, codigo_operativo,
                  fecha_programada, distrito, creado_en, actualizado_en, destinatario_nombre,
                  destinatario_telefono, direccion, referencia, latitud, longitud, repartidor_id_externo)
                VALUES (:id, :pedido, 'EN_CAMINO', 'TRK-PRIVADO', 'DSP-PRIVADO', '2026-11-07', 'San Borja',
                  '2026-11-06T10:00:00Z', '2026-11-06T12:00:00Z', 'NOMBRE-PRIVADO', 'TELEFONO-PRIVADO',
                  'DIRECCION-PRIVADA', 'REFERENCIA-PRIVADA', -12, -77, 'REPARTIDOR-PRIVADO')
                """).param("id", DESPACHO).param("pedido", PEDIDO).update();
    }

    @Test void devuelveSoloCamposPublicosYTransicionesOrdenadasSinDatosSensibles() throws Exception {
        // Inserción inversa: la consulta debe ordenar por la fecha del servidor.
        hito(3, "TRANSICION", "ASIGNADO", "EN_CAMINO", "2026-11-06T12:00:00Z", "{}");
        hito(2, "TRANSICION", "PENDIENTE_ASIGNACION", "ASIGNADO", "2026-11-06T11:00:00Z", "{}");
        hito(1, "TRANSICION", null, "PENDIENTE_ASIGNACION", "2026-11-06T10:00:00Z", "{}");
        hito(4, "REASIGNACION", "ASIGNADO", "ASIGNADO", "2026-11-06T11:30:00Z", "{}");
        hito(5, "REORDENAMIENTO", "ASIGNADO", "ASIGNADO", "2026-11-06T11:40:00Z", "{}");
        JsonNode respuesta = JSON.readTree(consultar(PEDIDO).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.idPedido").value(PEDIDO))
                .andExpect(jsonPath("$.estado").value("EN_CAMINO"))
                .andExpect(jsonPath("$.estadoEtiqueta").value("En camino"))
                .andExpect(jsonPath("$.distrito").value("San Borja"))
                .andExpect(jsonPath("$.fechaProgramada").value("2026-11-07"))
                .andExpect(jsonPath("$.actualizadoEn").value("2026-11-06T12:00:00Z"))
                .andReturn().getResponse().getContentAsString());
        assertThat(respuesta.propertyNames()).containsExactlyInAnyOrder("idPedido", "estado", "estadoEtiqueta",
                "fechaProgramada", "distrito", "hitos", "actualizadoEn");
        assertThat(respuesta.path("hitos").size()).isEqualTo(3);
        assertThat(respuesta.path("hitos").get(0).path("etiqueta").asText()).isEqualTo("En centro de despacho");
        assertThat(respuesta.path("hitos").get(1).path("etiqueta").asText()).isEqualTo("Asignado a repartidor");
        assertThat(respuesta.path("hitos").get(2).path("etiqueta").asText()).isEqualTo("En camino");
        for (JsonNode hito : respuesta.path("hitos")) {
            assertThat(hito.propertyNames()).containsExactlyInAnyOrder("estado", "etiqueta", "fecha");
        }
        assertThat(respuesta.toString()).doesNotContain("PRIVAD", "SECRETO", "REPARTIDOR", "EVIDENCIA");
        assertThat(sql.sql("SELECT count(*) FROM eventos_salientes").query(Long.class).single()).isZero();
    }

    @Test void conservaLasFechasHistoricasDeCadaReprogramacionSinExponerMotivosNiRecepcionInterna() throws Exception {
        sql.sql("UPDATE despachos SET estado='PENDIENTE_ASIGNACION', fecha_programada='2026-11-10' WHERE id=:id")
                .param("id", DESPACHO).update();
        hito(1, "TRANSICION", "EN_CAMINO", "FALLIDO", "2026-11-06T13:00:00Z", "{}");
        hito(2, "RECEPCION_EN_CENTRO", "FALLIDO", "FALLIDO", "2026-11-06T14:00:00Z", "{}");
        hito(3, "TRANSICION", "FALLIDO", "PENDIENTE_ASIGNACION", "2026-11-06T15:00:00Z",
                "{\"nuevaFechaProgramada\":\"2026-11-09\",\"comentario\":\"SECRETO\"}");
        hito(4, "TRANSICION", "EN_CAMINO", "FALLIDO", "2026-11-09T13:00:00Z", "{}");
        hito(5, "TRANSICION", "FALLIDO", "PENDIENTE_ASIGNACION", "2026-11-09T15:00:00Z",
                "{\"nuevaFechaProgramada\":\"2026-11-10\"}");
        consultar(PEDIDO).andExpect(status().isOk()).andExpect(jsonPath("$.hitos.length()").value(4))
                .andExpect(jsonPath("$.fechaProgramada").value("2026-11-10"))
                .andExpect(jsonPath("$.hitos[0].etiqueta").value("No entregado, regresando al centro"))
                .andExpect(jsonPath("$.hitos[1].etiqueta").value("Entrega reprogramada para 2026-11-09"))
                .andExpect(jsonPath("$.hitos[3].etiqueta").value("Entrega reprogramada para 2026-11-10"));
    }

    @Test void desempataHitosPorIdYAdmiteReprogramacionHistoricaSinFechaSinInventarla() throws Exception {
        hito(3, "TRANSICION", "FALLIDO", "PENDIENTE_ASIGNACION", "2026-11-06T12:00:00Z", "{}");
        hito(2, "TRANSICION", "EN_CAMINO", "FALLIDO", "2026-11-06T12:00:00Z", "{}");
        consultar(PEDIDO).andExpect(status().isOk())
                .andExpect(jsonPath("$.hitos[0].estado").value("FALLIDO"))
                .andExpect(jsonPath("$.hitos[1].etiqueta").value("Entrega reprogramada"));
    }

    @ParameterizedTest
    @CsvSource({"ENTREGADO,Entregado", "DEVUELTO_A_ORIGEN,Devuelto a origen", "CANCELADO,Cancelado"})
    void conservaElSeguimientoDeEstadosFinales(String estado, String etiqueta) throws Exception {
        sql.sql("UPDATE despachos SET estado=:estado WHERE id=:id").param("estado", estado).param("id", DESPACHO).update();
        hito(1, "TRANSICION", "EN_CAMINO", estado, "2026-11-06T12:00:00Z", "{}");
        consultar(PEDIDO).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value(estado))
                .andExpect(jsonPath("$.estadoEtiqueta").value(etiqueta)).andExpect(jsonPath("$.hitos[0].etiqueta").value(etiqueta));
    }

    @Test void noEncuentraOtrosPedidosNiSimulacionesNiInterpretaElIdComoSql() throws Exception {
        for (String pedido : List.of("PED-INEXISTENTE", "PED-000ABC", "' OR '1'='1")) {
            consultar(pedido).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DESP_ERROR_DESPACHO_NO_ENCONTRADO"));
        }
        sql.sql("UPDATE despachos SET es_simulado=true WHERE id=:id").param("id", DESPACHO).update();
        consultar(PEDIDO).andExpect(status().isNotFound());
    }

    private ResultActions consultar(String pedido) throws Exception {
        return http.perform(get("/api/v1/seguimientos/pedidos/{idPedido}", pedido).header("Authorization", "Bearer canal"));
    }

    private void hito(int orden, String operacion, String anterior, String nuevo, String fecha, String datos) {
        sql.sql("""
                INSERT INTO historial_estados_despacho(id, despacho_id, tipo_operacion, estado_anterior, estado_nuevo,
                  registrado_en, motivo_codigo, observaciones, ejecutor_id, datos)
                VALUES (:id, :despacho, :operacion, :anterior, :nuevo, :fecha, 'MOTIVO-SECRETO',
                  'COMENTARIO-SECRETO', 'ACTOR-PRIVADO', CAST(:datos AS jsonb))
                """).param("id", new UUID(0, orden)).param("despacho", DESPACHO).param("operacion", operacion)
                .param("anterior", anterior).param("nuevo", nuevo).param("fecha", OffsetDateTime.parse(fecha)).param("datos", datos).update();
    }
}
