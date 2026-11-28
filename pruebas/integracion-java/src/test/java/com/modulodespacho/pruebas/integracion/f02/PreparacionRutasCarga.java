package com.modulodespacho.pruebas.integracion.f02;

import static com.modulodespacho.pruebas.integracion.f02.EscenarioRutasF02.API;
import static com.modulodespacho.pruebas.integracion.f02.EscenarioRutasF02.exigirExito;
import static com.modulodespacho.pruebas.integracion.f02.EscenarioRutasF02.furgoneta;
import static com.modulodespacho.pruebas.integracion.f02.EscenarioRutasF02.repartidor;
import static com.modulodespacho.pruebas.integracion.f02.EscenarioRutasF02.tokenUsuario;

import com.fasterxml.jackson.databind.JsonNode;
import com.modulodespacho.pruebas.integracion.soporte.ClienteApi;
import com.modulodespacho.pruebas.integracion.soporte.EntornoCompose;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Preparación de PERF-05 y PERF-06. No es una prueba de la suite: Surefire solo la ejecuta con
 * {@code -Dtest=PreparacionRutasCarga}. Levanta el entorno con nombre fijo y lo deja activo para
 * JMeter, siembra 30 repartidores vinculados (perfiles repartidor-carga-01..30 de Seguridad
 * local) con una jornada activa y 10 despachos cada uno, espera la proyección y escribe el CSV de
 * rutas esperadas. El CSV no contiene tokens: JMeter los obtiene en su setUp.
 */
class PreparacionRutasCarga {
    private static final int REPARTIDORES = 30;
    private static final int DESPACHOS_POR_JORNADA = 10;
    private static final String PROYECTO = System.getProperty("entorno.proyecto", "hito5-perf");
    private static final Duration CONVERGENCIA_MAXIMA = Duration.ofSeconds(90);

    @Test
    void prepararRutas() throws Exception {
        var entorno = EntornoCompose.iniciarConservado(PROYECTO);
        var cliente = entorno.cliente();
        String gestor = tokenUsuario(cliente, "gestor");
        String zona = UUID.randomUUID().toString();
        String distrito = UUID.randomUUID().toString();
        entorno.sql("despacho_gestion", """
                INSERT INTO distrito(id,nombre) VALUES ('%1$s','Distrito de carga');
                INSERT INTO zonas(id,nombre,estado) VALUES ('%2$s','Zona de carga PERF-05','ACTIVO');
                INSERT INTO zonas_distritos(zona_id,distrito_id) VALUES ('%2$s','%1$s');
                INSERT INTO tarifas_zona(zona_id,tarifa_base,plazo_max_dias) VALUES ('%2$s',10,1);
                """.formatted(distrito, zona));

        var perfiles = new ArrayList<String>();
        var furgonetas = new ArrayList<String>();
        var sql = new StringBuilder();
        for (int indice = 1; indice <= REPARTIDORES; indice++) {
            String perfil = UUID.randomUUID().toString();
            String vehiculo = UUID.randomUUID().toString();
            perfiles.add(perfil);
            furgonetas.add(vehiculo);
            sql.append(repartidor(perfil, "Carga%02d".formatted(indice), "7000%04d".formatted(indice),
                    "98800%04d".formatted(indice), "carga%02d".formatted(indice), "CARGA-%02d".formatted(indice),
                    "55555555-5555-4555-8555-%012d".formatted(indice)));
            sql.append(furgoneta(vehiculo, "CAR-%03d".formatted(indice)));
        }
        entorno.sql("despacho_operacion", sql.toString());

        String hoy = LocalDate.now(ZoneId.of("America/Lima")).toString();
        var jornadas = new ArrayList<String>();
        for (int i = 0; i < REPARTIDORES; i++) {
            var jornada = cliente.postJson(API + "/jornadas", Map.of("idRepartidor", perfiles.get(i),
                    "idFurgoneta", furgonetas.get(i), "idZona", zona, "fecha", hoy), gestor, null);
            jornadas.add(exigirExito(jornada, "crear jornada " + (i + 1)).path("idJornada").asText());
            for (int d = 0; d < DESPACHOS_POR_JORNADA; d++) {
                var simulado = cliente.postJson(API + "/despachos/simulaciones",
                        Map.of("idZona", zona, "pesoKg", 2, "volumenM3", 0.01), gestor, null);
                String id = exigirExito(simulado, "simular despacho").path("idDespacho").asText();
                exigirExito(cliente.postJson(API + "/despachos/" + id + "/asignacion",
                        Map.of("idRepartidor", perfiles.get(i), "idJornada", jornadas.get(i)), gestor, null),
                        "asignar despacho");
            }
        }

        // Ruta esperada según Gestión (dueña de la secuencia); se exige que Mi Ruta ya coincida.
        var filas = new ArrayList<String>();
        filas.add("indice,idJornada,despachos");
        for (int i = 0; i < REPARTIDORES; i++) {
            int indice = i + 1;
            JsonNode ruta = cliente.get(API + "/jornadas/" + jornadas.get(i) + "/ruta", gestor).exigirJson(200);
            var esperados = new ArrayList<String>();
            for (JsonNode parada : ruta.path("despachos"))
                esperados.add(parada.path("idDespacho").asText() + ":" + parada.path("posicion").asInt());
            if (esperados.size() != DESPACHOS_POR_JORNADA)
                throw new IllegalStateException("La jornada " + indice + " tiene " + esperados.size() + " despachos");
            esperarMiRuta(cliente, "repartidor-carga-%02d".formatted(indice), jornadas.get(i), esperados);
            filas.add(indice + "," + jornadas.get(i) + "," + String.join(";", esperados));
        }

        Path datos = Path.of("..", "rendimiento", "datos");
        Files.createDirectories(datos);
        Files.write(datos.resolve("rutas-carga.csv"), filas, StandardCharsets.UTF_8);
        Files.writeString(datos.resolve("preparacion.txt"), """
                proyecto=%s
                fecha=%s
                repartidores=%d
                despachosPorJornada=%d
                idZona=%s
                """.formatted(PROYECTO, Instant.now(), REPARTIDORES, DESPACHOS_POR_JORNADA, zona));
        System.out.println("PERF_RUTAS_PREPARADAS proyecto=" + PROYECTO + " filas=" + (filas.size() - 1));
    }

    /** Espera a que la proyección muestre la misma jornada, despachos y secuencia en Mi Ruta. */
    private static void esperarMiRuta(ClienteApi cliente, String perfil, String jornada, List<String> esperados)
            throws InterruptedException {
        String token = tokenUsuario(cliente, perfil);
        Instant limite = Instant.now().plus(CONVERGENCIA_MAXIMA);
        String esperado = esperados.stream().sorted().collect(Collectors.joining(";"));
        while (Instant.now().isBefore(limite)) {
            var respuesta = cliente.get(API + "/repartidor/mi-ruta", token);
            if (respuesta.estado() == 200 && jornada.equals(respuesta.json().path("idJornada").asText())) {
                var obtenidos = new ArrayList<String>();
                for (JsonNode fila : respuesta.json().path("despachos"))
                    obtenidos.add(fila.path("idDespacho").asText() + ":" + fila.path("secuenciaRuta").asInt());
                if (esperado.equals(obtenidos.stream().sorted().collect(Collectors.joining(";")))) return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Mi Ruta no convergió para " + perfil);
    }
}
