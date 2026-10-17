package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosIncidenciaInconsistentesException;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.IncidenciaListadoLocal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL real con esquema de prueba; no acredita las migraciones ni los escritores de producción. */
class IncidenciasLocalesJdbcTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    private static JdbcClient cliente;
    private static IncidenciasLocalesJdbc lector;
    private static DriverManagerDataSource fuente;
    private static String esquema;

    @BeforeAll
    static void prepararEsquemaAislado() {
        if (Boolean.getBoolean("pruebas.postgres.externo")) {
            fuente = new DriverManagerDataSource(System.getProperty("spring.datasource.url"),
                    System.getProperty("spring.datasource.username"), System.getProperty("spring.datasource.password", ""));
            esquema = "prueba_incidencias_" + UUID.randomUUID().toString().replace("-", "");
            JdbcClient.create(fuente).sql("CREATE SCHEMA " + esquema).update();
            fuente.setSchema(esquema);
        } else {
            POSTGRES.start();
            fuente = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
        new ResourceDatabasePopulator(new ClassPathResource("incidencias/esquema-lectura-fallidos.sql"))
                .execute(fuente);
        cliente = JdbcClient.create(fuente);
        lector = new IncidenciasLocalesJdbc(cliente);
    }

    @AfterAll
    static void cerrarBaseAislada() {
        if (esquema != null) {
            fuente.setSchema("public");
            JdbcClient.create(fuente).sql("DROP SCHEMA " + esquema + " CASCADE").update();
        } else if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @BeforeEach
    void prepararDatos() {
        cliente.sql("TRUNCATE intentos_entrega, despachos, motivos_fallo, parametros_configuracion").update();
        cliente.sql("INSERT INTO parametros_configuracion (clave, valor) VALUES ('MAXIMO_INTENTOS', '2')").update();
        cliente.sql("""
                INSERT INTO motivos_fallo (codigo, etiqueta, seleccionable_en_campo, consume_intento, activo)
                VALUES ('CLIENTE_AUSENTE', 'Cliente ausente', true, true, true),
                       ('NO_INTENTADO', 'No intentado por fin de jornada', false, false, true),
                       ('HISTORICO', 'Motivo histórico inactivo', true, true, false)
                """).update();
    }

    @Test
    void seleccionaSoloFallidosConSuIncidenciaVigenteSinHeredarRecepcionNiEvidencia() {
        crearDespacho(1, "FALLIDO", 1, 12L, false);
        crearIncidencia(11, 1, "CLIENTE_AUSENTE", "2026-10-14T18:00:00Z", true, true);
        crearIncidencia(12, 1, "NO_INTENTADO", "2026-10-16T18:00:00Z", false, false);
        crearDespacho(2, "FALLIDO", 2, 21L, true);
        crearIncidencia(21, 2, "HISTORICO", "2026-10-15T18:00:00Z", true, true);
        int numero = 3;
        for (String estado : List.of("PENDIENTE_ASIGNACION", "ASIGNADO", "EN_CAMINO", "ENTREGADO", "DEVUELTO_A_ORIGEN", "CANCELADO")) {
            crearDespacho(numero++, estado, 0, null, false);
        }

        List<IncidenciaListadoLocal> base = lector.consultarBase(null, null, null);

        assertThat(base).hasSize(2);
        IncidenciaListadoLocal vigente = base.getFirst();
        assertThat(vigente.idDespacho()).isEqualTo(id(1).toString());
        assertThat(vigente.idIncidencia()).isEqualTo(id(12).toString());
        assertThat(vigente.versionDespacho()).isEqualTo(4);
        assertThat(vigente.motivo().codigo()).isEqualTo("NO_INTENTADO");
        assertThat(vigente.numeroIntentos()).isEqualTo(1);
        assertThat(vigente.maximoIntentos()).isEqualTo(2);
        assertThat(vigente.consumeIntento()).isFalse();
        assertThat(vigente.recibidoEnCentro()).isFalse();
        assertThat(vigente.tieneEvidencia()).isFalse();
        assertThat(vigente.idRepartidor()).isEqualTo(id(1012).toString());
        assertThat(vigente.idJornada()).isEqualTo(id(2012).toString());
        assertThat(base.getLast().motivo().etiqueta()).isEqualTo("Motivo histórico inactivo");
        assertThat(base.getLast().recibidoEnCentro()).isTrue();
        assertThat(base.getLast().tieneEvidencia()).isTrue();
        assertThat(base.getLast().pedidoAnulado()).isTrue();
        assertThat(cliente.sql("SELECT count(*) FROM intentos_entrega").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void aplicaDiasInclusivosDeLimaConLimitesAbiertosYMotivosHistoricos() {
        List<String> fechas = List.of("2026-10-15T04:59:59.999Z", "2026-10-15T05:00:00Z",
                "2026-10-16T04:59:59.999Z", "2026-10-16T05:00:00Z");
        for (int indice = 0; indice < fechas.size(); indice++) {
            int numero = indice + 1;
            crearDespacho(numero, "FALLIDO", 1, (long) numero + 10, false);
            crearIncidencia(numero + 10, numero, numero == 3 ? "HISTORICO" : "CLIENTE_AUSENTE",
                    fechas.get(indice), false, false);
        }
        LocalDate dia = LocalDate.of(2026, 10, 15);

        assertThat(lector.consultarBase(null, dia, dia)).extracting(IncidenciaListadoLocal::idDespacho)
                .containsExactly(id(2).toString(), id(3).toString());
        assertThat(lector.consultarBase(null, null, dia)).hasSize(3);
        assertThat(lector.consultarBase(null, dia, null)).hasSize(3);
        assertThat(lector.consultarBase(null, null, null)).hasSize(4);
        assertThat(lector.consultarBase("HISTORICO", dia, dia)).extracting(IncidenciaListadoLocal::idDespacho)
                .containsExactly(id(3).toString());
        assertThat(lector.existeCodigo("HISTORICO")).isTrue();
        assertThat(lector.existeCodigo("NO_EXISTE")).isFalse();
        assertThat(lector.consultarBase(null, LocalDate.of(2099, 1, 1), null)).isEmpty();
        assertThatThrownBy(() -> lector.consultarBase(null, dia.plusDays(1), dia))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaReferenciasVigentesAusentesAjenasOQueNoCorrespondenAUnFallo() {
        crearDespacho(1, "FALLIDO", 1, null, false);
        assertThatThrownBy(() -> lector.consultarBase(null, null, null))
                .isInstanceOf(DatosIncidenciaInconsistentesException.class).hasMessageContaining("incidencia vigente");

        crearDespacho(2, "ENTREGADO", 1, 21L, false);
        crearIncidencia(21, 2, "CLIENTE_AUSENTE", "2026-10-15T18:00:00Z", false, false);
        cliente.sql("UPDATE despachos SET ultima_incidencia_id = :incidencia WHERE id = :despacho")
                .param("incidencia", id(21)).param("despacho", id(1)).update();
        assertThatThrownBy(() -> lector.consultarBase(null, null, null))
                .isInstanceOf(DatosIncidenciaInconsistentesException.class).hasMessageContaining("incidencia vigente");

        crearIncidencia(11, 1, "CLIENTE_AUSENTE", "2026-10-15T18:00:00Z", false, false);
        cliente.sql("UPDATE intentos_entrega SET resultado = 'ENTREGADO' WHERE id = :id").param("id", id(11)).update();
        cliente.sql("UPDATE despachos SET ultima_incidencia_id = :incidencia WHERE id = :despacho")
                .param("incidencia", id(11)).param("despacho", id(1)).update();
        assertThatThrownBy(() -> lector.consultarBase(null, null, null))
                .isInstanceOf(DatosIncidenciaInconsistentesException.class).hasMessageContaining("incidencia vigente");
    }

    @Test
    void leeElMaximoConfiguradoSinInventarUnaPoliticaSiFaltaOEsInvalida() {
        crearDespacho(1, "FALLIDO", 1, 11L, false);
        crearIncidencia(11, 1, "CLIENTE_AUSENTE", "2026-10-15T18:00:00Z", false, false);
        cliente.sql("DELETE FROM parametros_configuracion").update();
        assertThatThrownBy(() -> lector.consultarBase(null, null, null))
                .isInstanceOf(DatosIncidenciaInconsistentesException.class).hasMessageContaining("MAXIMO_INTENTOS");
        for (String valor : List.of("sin-configurar", "0", "3")) {
            cliente.sql("""
                    INSERT INTO parametros_configuracion (clave, valor) VALUES ('MAXIMO_INTENTOS', :valor)
                    ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor
                    """).param("valor", valor).update();
            if (valor.equals("3")) {
                assertThat(lector.consultarBase(null, null, null).getFirst().maximoIntentos()).isEqualTo(3);
            } else {
                assertThatThrownBy(() -> lector.consultarBase(null, null, null))
                        .isInstanceOf(DatosIncidenciaInconsistentesException.class).hasMessageContaining("MAXIMO_INTENTOS");
            }
        }
    }

    private void crearDespacho(long numero, String estado, int intentos, Long incidencia, boolean anulado) {
        cliente.sql("""
                INSERT INTO despachos (id, estado, version, codigo_rastreo_interno, numero_intentos,
                    ultima_incidencia_id, pedido_anulado_en, repartidor_id_externo, asignacion_diaria_id_externa)
                VALUES (:id, :estado, 4, :codigo, :intentos, :incidencia, :anulado, :repartidor, :jornada)
                """).param("id", id(numero)).param("estado", estado).param("codigo", "TRK-" + numero)
                .param("intentos", intentos).param("incidencia", incidencia == null ? null : id(incidencia))
                .param("anulado", anulado ? OffsetDateTime.parse("2026-10-16T14:00:00Z") : null)
                .param("repartidor", id(9000 + numero)).param("jornada", id(10000 + numero)).update();
    }

    private void crearIncidencia(long numero, long despacho, String motivo, String fecha, boolean recibido, boolean evidencia) {
        cliente.sql("""
                INSERT INTO intentos_entrega (id, despacho_id, resultado, motivo_codigo, consume_intento,
                    repartidor_id_externo, asignacion_diaria_id_externa, registrado_en, recibido_en_centro_en, evidencia_id_externa)
                VALUES (:id, :despacho, 'FALLIDO', :motivo, :consume, :repartidor, :jornada, :fecha, :recibido, :evidencia)
                """).param("id", id(numero)).param("despacho", id(despacho)).param("motivo", motivo)
                .param("consume", !motivo.equals("NO_INTENTADO"))
                .param("repartidor", id(1000 + numero)).param("jornada", id(2000 + numero))
                .param("fecha", OffsetDateTime.parse(fecha)).param("recibido", recibido ? OffsetDateTime.parse(fecha) : null)
                .param("evidencia", evidencia ? id(3000 + numero) : null).update();
    }

    private static UUID id(long numero) {
        return new UUID(0, numero);
    }
}
