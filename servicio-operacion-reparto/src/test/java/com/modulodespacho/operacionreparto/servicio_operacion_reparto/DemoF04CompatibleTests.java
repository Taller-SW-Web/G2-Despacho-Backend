package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

class DemoF04CompatibleTests {
    @Test
    void conservaCargaRepetibleDeDemoConLasRestriccionesNuevas() throws Exception {
        try (var base = new PostgreSQLContainer("postgres:17.11").withDatabaseName("f04_operacion_demo")) {
            base.start();
            Flyway.configure().dataSource(base.getJdbcUrl(), base.getUsername(), base.getPassword()).load().migrate();
            try (var conexion = DriverManager.getConnection(base.getJdbcUrl(), base.getUsername(), base.getPassword());
                    var sentencia = conexion.createStatement()) {
                String datos = new ClassPathResource("flota/datos-demo-f04.sql").getContentAsString(StandardCharsets.UTF_8);
                sentencia.execute(datos);
                sentencia.execute(datos);
                try (var filas = sentencia.executeQuery("""
                        SELECT count(*), count(*) FILTER (WHERE estado = 'CERRADA') FROM asignaciones_diarias
                        """)) {
                    filas.next();
                    assertThat(filas.getInt(1)).isEqualTo(12);
                    assertThat(filas.getInt(2)).isEqualTo(2);
                }
            }
        }
    }
}
