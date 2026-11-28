package com.modulodespacho.pruebas.integracion.f02;

import static com.modulodespacho.pruebas.integracion.f02.EscenarioRutasF02.API;
import static com.modulodespacho.pruebas.integracion.f02.EscenarioRutasF02.exigirExito;
import static com.modulodespacho.pruebas.integracion.f02.EscenarioRutasF02.tokenUsuario;

import com.fasterxml.jackson.databind.JsonNode;
import com.modulodespacho.pruebas.integracion.soporte.EntornoCompose;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Preparación de PERF-02 (ES-F02-03 RN-05). No es una prueba de la suite: Surefire solo la
 * ejecuta con {@code -Dtest=PreparacionColaCarga}. Levanta un entorno limpio con nombre fijo y lo
 * deja activo para JMeter, siembra exactamente 10.000 despachos en PENDIENTE_ASIGNACION por la API
 * de simulación, uno tras otro, y escribe los IDs esperados de las páginas 1, 50 y 100.
 *
 * <p>La cola ordena por fecha_programada, creado_en e id. Todos los despachos son de hoy y se
 * crean en secuencia, así que el orden esperado es el de creación y no depende de la respuesta
 * del sistema bajo prueba. La preparación lo contrasta con la API antes de terminar.
 */
class PreparacionColaCarga {
    private static final int DESPACHOS = 10_000;
    private static final int TAMANO_PAGINA = 100;
    private static final List<Integer> PAGINAS = List.of(1, 50, 100);
    private static final String PROYECTO = System.getProperty("entorno.proyecto", "hito5-perf");

    @Test
    void prepararCola() throws Exception {
        var entorno = EntornoCompose.iniciarConservado(PROYECTO);
        var cliente = entorno.cliente();
        String gestor = tokenUsuario(cliente, "gestor");
        String zona = UUID.randomUUID().toString();
        String distrito = UUID.randomUUID().toString();
        entorno.sql("despacho_gestion", """
                INSERT INTO distrito(id,nombre) VALUES ('%1$s','Distrito de carga');
                INSERT INTO zonas(id,nombre,estado) VALUES ('%2$s','Zona de carga PERF-02','ACTIVO');
                INSERT INTO zonas_distritos(zona_id,distrito_id) VALUES ('%2$s','%1$s');
                INSERT INTO tarifas_zona(zona_id,tarifa_base,plazo_max_dias) VALUES ('%2$s',10,1);
                """.formatted(distrito, zona));

        Instant inicio = Instant.now();
        var ids = new ArrayList<String>(DESPACHOS);
        for (int i = 0; i < DESPACHOS; i++) {
            // El token de usuario dura 15 min; se renueva antes de que caduque durante la siembra.
            if (i > 0 && i % 2_000 == 0) gestor = tokenUsuario(cliente, "gestor");
            var simulado = cliente.postJson(API + "/despachos/simulaciones",
                    Map.of("idZona", zona, "pesoKg", 2, "volumenM3", 0.01), gestor, null);
            ids.add(exigirExito(simulado, "simular despacho " + (i + 1)).path("idDespacho").asText());
        }
        Duration siembra = Duration.between(inicio, Instant.now());

        List<String> conteo = entorno.consultar("despacho_gestion",
                "SELECT count(*) FROM despachos WHERE estado='PENDIENTE_ASIGNACION';");
        if (!List.of(String.valueOf(DESPACHOS)).equals(conteo))
            throw new IllegalStateException("Se esperaban " + DESPACHOS + " pendientes y hay " + conteo);

        var filas = new ArrayList<String>();
        filas.add("pagina,ids");
        for (int pagina : PAGINAS) {
            List<String> esperados = ids.subList((pagina - 1) * TAMANO_PAGINA, pagina * TAMANO_PAGINA);
            JsonNode respuesta = cliente.get(API + "/despachos/pendientes?pagina=" + pagina
                    + "&tamanoPagina=" + TAMANO_PAGINA, gestor).exigirJson(200);
            var obtenidos = new ArrayList<String>();
            for (JsonNode fila : respuesta.path("contenido")) obtenidos.add(fila.path("idDespacho").asText());
            if (respuesta.path("totalElementos").asLong() != DESPACHOS
                    || respuesta.path("totalPaginas").asLong() != DESPACHOS / TAMANO_PAGINA
                    || !esperados.equals(obtenidos))
                throw new IllegalStateException("La página " + pagina + " no coincide con el orden de creación");
            filas.add(pagina + "," + String.join(";", esperados));
        }

        Path datos = Path.of("..", "rendimiento", "datos");
        Files.createDirectories(datos);
        Files.write(datos.resolve("paginas-cola.csv"), filas, StandardCharsets.UTF_8);
        Files.writeString(datos.resolve("preparacion-cola.txt"), """
                proyecto=%s
                fecha=%s
                despachosPendientes=%s
                siembraSegundos=%d
                idZona=%s
                paginasVerificadas=%s
                """.formatted(PROYECTO, Instant.now(), conteo.get(0), siembra.toSeconds(), zona, PAGINAS));
        System.out.println("PERF_COLA_PREPARADA proyecto=" + PROYECTO + " pendientes=" + conteo.get(0)
                + " siembra=" + siembra.toSeconds() + "s");
    }
}
