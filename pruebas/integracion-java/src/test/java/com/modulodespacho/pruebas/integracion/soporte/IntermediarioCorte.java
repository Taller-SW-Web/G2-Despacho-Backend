package com.modulodespacho.pruebas.integracion.soporte;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Intermediario HTTP de prueba entre Gestión y Operación. Reenvía todo sin cambios, salvo la
 * primera transferencia de capacidad tras {@link #armarCorte()}: la entrega a Operación, espera
 * su respuesta completa (la confirmación ya es durable) y cierra la conexión hacia Gestión sin
 * responder. Registra cada transferencia sin cabeceras de autorización.
 */
public final class IntermediarioCorte implements AutoCloseable {
    public static final String RUTA_TRANSFERENCIA = "/internal/v1/capacidad/transferencias";
    private static final Set<String> RESTRINGIDAS =
            Set.of("host", "connection", "content-length", "expect", "upgrade", "transfer-encoding");

    /** Evidencia de una transferencia vista por el intermediario. */
    public record Evento(
            int numero,
            String claveIdempotencia,
            Instant recibida,
            int estadoOperacion,
            String estadoTransferencia,
            String idNuevaReserva,
            Instant respuestaOperacion,
            boolean cortada,
            Instant corte) {}

    private final HttpServer servidor;
    private final String destino;
    private String controlRemoto;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean corteArmado = new AtomicBoolean();
    private final List<Evento> eventos = new ArrayList<>();

    private IntermediarioCorte(String destino) throws IOException {
        this.destino = destino;
        // Docker Desktop entrega host.docker.internal a través de loopback del anfitrión.
        String escucha = System.getProperty("intermediario.escucha", "127.0.0.1");
        servidor = HttpServer.create(new InetSocketAddress(escucha, Integer.getInteger("intermediario.puerto", 0)), 0);
        servidor.setExecutor(Executors.newCachedThreadPool());
        servidor.createContext("/actuator/health", e -> responder(e, "{\"status\":\"UP\"}"));
        servidor.createContext("/pruebas/armar", e -> {
            if (!"POST".equals(e.getRequestMethod())) { e.sendResponseHeaders(405,-1); e.close(); return; }
            corteArmado.set(true);
            responder(e, "{}");
        });
        servidor.createContext("/pruebas/eventos", e -> {
            var lista = new ArrayList<java.util.Map<String,Object>>();
            for (var v : eventos()) {
                var m = new java.util.LinkedHashMap<String,Object>();
                m.put("numero",v.numero()); m.put("claveIdempotencia",v.claveIdempotencia());
                m.put("recibida",v.recibida().toString()); m.put("estadoOperacion",v.estadoOperacion());
                m.put("estadoTransferencia",v.estadoTransferencia()); m.put("idNuevaReserva",v.idNuevaReserva());
                m.put("respuestaOperacion",v.respuestaOperacion().toString()); m.put("cortada",v.cortada());
                m.put("corte",v.corte()==null ? null : v.corte().toString());
                lista.add(m);
            }
            responder(e, ClienteApi.JSON.writeValueAsString(lista));
        });
        servidor.createContext("/", this::atender);
    }

    /** El mismo intermediario Java dentro de la red Compose, sin callbacks al host. */
    public static void main(String[] args) throws Exception {
        System.setProperty("intermediario.escucha","0.0.0.0");
        System.setProperty("intermediario.puerto","19099");
        iniciar("http://operacion:8082");
        new java.util.concurrent.CountDownLatch(1).await();
    }

    private IntermediarioCorte(String control, boolean remoto) {
        this.destino = null;
        this.servidor = null;
        this.controlRemoto = control;
    }

    public static IntermediarioCorte conectar(String control) throws InterruptedException {
        var limite = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(limite)) {
            try {
                if (new ClienteApi().get(control+"/actuator/health",null).estado()==200)
                    return new IntermediarioCorte(control,true);
            } catch (RuntimeException aunNoIniciado) { }
            Thread.sleep(500);
        }
        throw new IllegalStateException("No inició el intermediario en Compose");
    }

    private static void responder(HttpExchange e, String texto) throws IOException {
        byte[] bytes = texto.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type","application/json");
        e.sendResponseHeaders(200,bytes.length);
        try (var salida=e.getResponseBody()) { salida.write(bytes); }
        e.close();
    }

    public static IntermediarioCorte iniciar(String destino) throws IOException {
        var intermediario = new IntermediarioCorte(destino);
        intermediario.servidor.start();
        return intermediario;
    }

    public int puerto() {
        return servidor.getAddress().getPort();
    }

    /** La próxima transferencia se confirma en Operación y su respuesta no llega a Gestión. */
    public void armarCorte() {
        if (controlRemoto != null) {
            new ClienteApi().postJson(controlRemoto+"/pruebas/armar",java.util.Map.of(),null,null).exigirJson(200);
            return;
        }
        corteArmado.set(true);
    }

    public synchronized List<Evento> eventos() {
        if (controlRemoto != null) {
            var lista = new ArrayList<Evento>();
            for (var v : new ClienteApi().get(controlRemoto+"/pruebas/eventos",null).exigirJson(200)) {
                lista.add(new Evento(v.path("numero").asInt(),v.path("claveIdempotencia").asText(),
                    Instant.parse(v.path("recibida").asText()),v.path("estadoOperacion").asInt(),
                    v.path("estadoTransferencia").asText(),v.path("idNuevaReserva").asText(),
                    Instant.parse(v.path("respuestaOperacion").asText()),v.path("cortada").asBoolean(),
                    v.path("corte").isNull() ? null : Instant.parse(v.path("corte").asText())));
            }
            return lista;
        }
        return List.copyOf(eventos);
    }

    private void atender(HttpExchange intercambio) throws IOException {
        try {
            byte[] cuerpo = intercambio.getRequestBody().readAllBytes();
            var solicitud =
                    HttpRequest.newBuilder(
                                    URI.create(destino + intercambio.getRequestURI().toString()))
                            .timeout(Duration.ofSeconds(30))
                            .method(
                                    intercambio.getRequestMethod(),
                                    cuerpo.length == 0
                                            ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofByteArray(cuerpo));
            intercambio.getRequestHeaders().forEach((nombre, valores) -> {
                if (!RESTRINGIDAS.contains(nombre.toLowerCase()))
                    valores.forEach(valor -> solicitud.header(nombre, valor));
            });
            boolean esTransferencia =
                    "POST".equals(intercambio.getRequestMethod())
                            && RUTA_TRANSFERENCIA.equals(intercambio.getRequestURI().getPath());
            Instant recibida = Instant.now();
            HttpResponse<byte[]> respuesta =
                    http.send(solicitud.build(), HttpResponse.BodyHandlers.ofByteArray());
            Instant respondida = Instant.now();

            if (esTransferencia) {
                boolean cortar = corteArmado.compareAndSet(true, false);
                registrar(intercambio, recibida, respuesta, respondida, cortar);
                if (cortar) {
                    // Sin cabeceras ni cuerpo: Gestión ve la conexión cerrada (respuesta perdida).
                    intercambio.close();
                    return;
                }
            }
            respuesta.headers().map().forEach((nombre, valores) -> {
                if (!RESTRINGIDAS.contains(nombre.toLowerCase()) && !nombre.startsWith(":"))
                    valores.forEach(valor -> intercambio.getResponseHeaders().add(nombre, valor));
            });
            byte[] salida = respuesta.body();
            intercambio.sendResponseHeaders(
                    respuesta.statusCode(), salida.length == 0 ? -1 : salida.length);
            if (salida.length > 0)
                try (var escritura = intercambio.getResponseBody()) {
                    escritura.write(salida);
                }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            try {
                intercambio.sendResponseHeaders(502, -1);
            } catch (IOException cabecerasYaEnviadas) {
                // La conexión se cierra abajo de todos modos.
            }
        } finally {
            intercambio.close();
        }
    }

    private synchronized void registrar(
            HttpExchange intercambio,
            Instant recibida,
            HttpResponse<byte[]> respuesta,
            Instant respondida,
            boolean cortada) {
        String estado = null;
        String reserva = null;
        try {
            JsonNode cuerpo = ClienteApi.JSON.readTree(respuesta.body());
            estado = cuerpo.path("estado").asText(null);
            reserva = cuerpo.path("idNuevaReserva").asText(null);
        } catch (IOException noEsJson) {
            // Se registra solo el estado HTTP.
        }
        eventos.add(
                new Evento(
                        eventos.size() + 1,
                        intercambio.getRequestHeaders().getFirst("Idempotency-Key"),
                        recibida,
                        respuesta.statusCode(),
                        estado,
                        reserva,
                        respondida,
                        cortada,
                        cortada ? Instant.now() : null));
    }

    @Override
    public void close() {
        if (servidor != null) servidor.stop(0);
    }
}
