package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Reenvío manual sobre PostgreSQL aislado; no usa Flyway ni datos de Supabase. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReenvioEventosVentasTests {
    private PostgreSQLContainer postgres;
    private JdbcClient sql;
    private ReenvioEventosVentas reenvios;

    @BeforeAll
    void preparar() {
        postgres = new PostgreSQLContainer("postgres:17.11");
        postgres.start();
        sql =
                JdbcClient.create(
                        new DriverManagerDataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword()));
        sql.sql(
                        """
                CREATE TABLE eventos_salientes (
                    id uuid PRIMARY KEY, despacho_id uuid, destino text NOT NULL, payload jsonb NOT NULL,
                    estado_envio text NOT NULL DEFAULT 'PENDIENTE', intentos_envio integer NOT NULL DEFAULT 0,
                    proximo_intento_en timestamptz DEFAULT now(), bloqueada_hasta timestamptz, bloqueo_id uuid,
                    creado_en timestamptz DEFAULT now(), enviado_en timestamptz, actualizado_en timestamptz DEFAULT now(), ultimo_error text)
                """)
                .update();
        reenvios = new ReenvioEventosVentas(sql);
    }

    @AfterAll
    void cerrar() {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void limpiar() {
        sql.sql("DELETE FROM eventos_salientes").update();
    }

    @Test
    void devuelveALaColaUnEventoFallidoSinCambiarSuContenido() {
        UUID despacho = UUID.randomUUID();
        UUID evento = insertar(despacho, "VENTAS", "ENVIO_FALLIDO", 6);
        String payload = payload(evento);

        var resultado =
                reenvios.reenviar(
                        despacho.toString().toUpperCase(), evento.toString(), "gestor-1");

        assertThat(resultado.idEvento()).isEqualTo(evento.toString());
        assertThat(resultado.estadoEnvio()).isEqualTo("PENDIENTE");
        var fila =
                sql.sql(
                                "SELECT estado_envio, intentos_envio, proximo_intento_en <= now() AS listo, bloqueo_id FROM eventos_salientes WHERE id = :id")
                        .param("id", evento)
                        .query(
                                (r, i) ->
                                        new Object[] {
                                            r.getString(1),
                                            r.getInt(2),
                                            r.getBoolean(3),
                                            r.getObject(4)
                                        })
                        .single();
        assertThat(fila).containsExactly("PENDIENTE", 0, true, null);
        assertThat(payload(evento)).isEqualTo(payload);
    }

    @Test
    void rechazaUnEventoQueNoEstaEnEnvioFallido() {
        UUID despacho = UUID.randomUUID();
        UUID evento = insertar(despacho, "VENTAS", "ENVIADO", 1);

        assertThatThrownBy(
                        () -> reenvios.reenviar(despacho.toString(), evento.toString(), "gestor-1"))
                .isInstanceOfSatisfying(
                        ErrorDeNegocio.class,
                        error -> {
                            assertThat(error.getEstado()).isEqualTo(409);
                            assertThat(error.getCodigo())
                                    .isEqualTo("DESP_ERROR_EVENTO_NO_REENVIABLE");
                            assertThat(error.getEstadoActual()).isEqualTo("ENVIADO");
                        });
    }

    @Test
    void noReenviaEventosDeOtroDespachoNiInternos() {
        UUID despacho = UUID.randomUUID();
        UUID ajeno = insertar(UUID.randomUUID(), "VENTAS", "ENVIO_FALLIDO", 6);
        UUID interno = insertar(despacho, "OPERACION", "ENVIO_FALLIDO", 6);

        for (UUID evento : new UUID[] {ajeno, interno})
            assertThatThrownBy(
                            () ->
                                    reenvios.reenviar(
                                            despacho.toString(), evento.toString(), "gestor-1"))
                    .isInstanceOfSatisfying(
                            ErrorDeNegocio.class,
                            error -> {
                                assertThat(error.getEstado()).isEqualTo(404);
                                assertThat(error.getCodigo())
                                        .isEqualTo("DESP_ERROR_EVENTO_NO_ENCONTRADO");
                            });
        assertThat(
                        sql.sql(
                                        "SELECT count(*) FROM eventos_salientes WHERE estado_envio = 'ENVIO_FALLIDO'")
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void validaQueLosIdentificadoresSeanUuid() {
        assertThatThrownBy(() -> reenvios.reenviar("DSP-000123", UUID.randomUUID().toString(), "g"))
                .isInstanceOfSatisfying(
                        ErrorDeNegocio.class,
                        error -> assertThat(error.getEstado()).isEqualTo(400));
    }

    private UUID insertar(UUID despacho, String destino, String estado, int intentos) {
        UUID id = UUID.randomUUID();
        sql.sql(
                        """
                INSERT INTO eventos_salientes(id, despacho_id, destino, payload, estado_envio, intentos_envio, proximo_intento_en)
                VALUES (:id, :despacho, :destino, CAST(:payload AS jsonb), :estado, :intentos, now() + interval '1 hour')
                """)
                .param("id", id)
                .param("despacho", despacho)
                .param("destino", destino)
                .param("payload", "{\"idEvento\":\"" + id + "\",\"tipo\":\"DESPACHO_EN_CAMINO\"}")
                .param("estado", estado)
                .param("intentos", intentos)
                .update();
        return id;
    }

    private String payload(UUID evento) {
        return sql.sql("SELECT payload::text FROM eventos_salientes WHERE id = :id")
                .param("id", evento)
                .query(String.class)
                .single();
    }
}
