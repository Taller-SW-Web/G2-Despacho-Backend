package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

/** Outbox real sobre PostgreSQL aislado; no usa Flyway ni datos de Supabase. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntregaEventosDemoTests {
    private PostgreSQLContainer postgres;
    private JdbcClient sql;

    @BeforeAll void preparar() {
        postgres=new PostgreSQLContainer("postgres:17.11");
        postgres.start();
        sql=JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        sql.sql("""
                CREATE TABLE eventos_salientes (
                    id uuid PRIMARY KEY, destino text NOT NULL, payload jsonb NOT NULL,
                    estado_envio text NOT NULL DEFAULT 'PENDIENTE', intentos_envio integer NOT NULL DEFAULT 0,
                    proximo_intento_en timestamptz DEFAULT now(), bloqueada_hasta timestamptz, bloqueo_id uuid,
                    creado_en timestamptz DEFAULT now(), enviado_en timestamptz, actualizado_en timestamptz DEFAULT now(), ultimo_error text)
                """).update();
    }
    @AfterAll void cerrar() { if(postgres!=null) postgres.close(); }

    @ParameterizedTest
    @CsvSource({"true,OPERACION,ENVIADO","false,OPERACION,PENDIENTE","true,VENTAS,PENDIENTE"})
    void demoSoloEximeLaProyeccionInternaSinPerderEventos(boolean demo,String destino,String estadoEsperado) {
        sql.sql("DELETE FROM eventos_salientes").update();
        UUID evento=UUID.randomUUID(),despacho=UUID.randomUUID();
        sql.sql("INSERT INTO eventos_salientes(id,destino,payload) VALUES (:id,:destino,CAST(:payload AS jsonb))")
                .param("id",evento).param("destino",destino).param("payload","{\"idDespacho\":\""+despacho+"\"}").update();
        var constructor=RestClient.builder().baseUrl("http://operacion-prueba");
        var servidor=MockRestServiceServer.bindTo(constructor).build();
        if("ENVIADO".equals(estadoEsperado)) servidor.expect(requestTo("http://operacion-prueba/internal/v1/proyecciones/despachos/"+despacho))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON)).andRespond(withNoContent());
        var cliente=constructor.build();
        new EntregaEventosProgramacion(sql,JsonMapper.builder().build(),cliente,()->"",cliente,()->"",demo).ejecutar();
        assertThat(sql.sql("SELECT estado_envio FROM eventos_salientes WHERE id=:id").param("id",evento).query(String.class).single())
                .isEqualTo(estadoEsperado);
        assertThat(sql.sql("SELECT intentos_envio FROM eventos_salientes WHERE id=:id").param("id",evento).query(Integer.class).single()).isEqualTo(1);
        servidor.verify();
    }
}
