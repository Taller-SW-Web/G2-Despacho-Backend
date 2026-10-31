package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.time.Clock;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure.RecepcionCentroJdbc;
import static org.mockito.Mockito.mock;

/** Arranque exclusivo del classpath de pruebas para el recorrido Playwright de F-04. */
public final class CierreNavegadorPruebas {
    @TestConfiguration static class IdentidadQA {
        @Bean @Primary JwtDecoder decodificadorQA() { return mock(JwtDecoder.class); }
    }
    public static void main(String[] argumentos) throws Exception {
        var app = new SpringApplication(ServicioGestionDespachosApplication.class, TestcontainersConfiguration.class,
                CierreDespachoHttpTests.RelojPruebas.class, IdentidadQA.class);
        var propiedades = Map.of("server.port", "18086", "SEGURIDAD_URL", "http://127.0.0.1:1",
                "despacho.operacion.url", "http://127.0.0.1:1", "pruebas.postgres.externo", "false",
                "despacho.programacion.recuperacion-habilitada", "false", "despacho.recepcion.procesador-habilitado", "false");
        // Propiedades de línea de comandos: no heredar la base de trabajo del desarrollador.
        String[] opciones = Stream.concat(Stream.of(argumentos), propiedades.entrySet().stream()
                .map(entrada -> "--" + entrada.getKey() + "=" + entrada.getValue())).toArray(String[]::new);
        try (var contexto = app.run(opciones)) {
            var postgres = contexto.getBean(PostgreSQLContainer.class);
            try (var conexion = contexto.getBean(DataSource.class).getConnection()) {
                if (!conexion.getMetaData().getURL().contains(":" + postgres.getMappedPort(5432) + "/"))
                    throw new IllegalStateException("La revisión exige el contenedor desechable");
            }
            var datos = new CierreDespachoHttpTests();
            datos.sql = contexto.getBean(JdbcClient.class); datos.reloj = contexto.getBean(Clock.class);
            datos.recepciones = contexto.getBean(RecepcionCentroJdbc.class);
            datos.decodificador = contexto.getBean(JwtDecoder.class); datos.preparar();
            System.out.println("REVISION_CIERRE_F04_LISTA: Gestión real aislada, despacho " + CierreDespachoHttpTests.DESPACHO);
            var detener = java.nio.file.Path.of("target/detener-revision-cierre-f04");
            long limite = System.nanoTime() + java.time.Duration.ofMinutes(15).toNanos();
            while (!java.nio.file.Files.exists(detener) && System.nanoTime() < limite) Thread.sleep(1000);
        }
    }
}
