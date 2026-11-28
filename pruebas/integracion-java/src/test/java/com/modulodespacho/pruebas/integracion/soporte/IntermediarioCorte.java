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
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean corteArmado = new AtomicBoolean();
    private final List<Evento> eventos = new ArrayList<>();

    private IntermediarioCorte(String destino) throws IOException {
        this.destino = destino;
        // Docker Desktop entrega host.docker.internal a través de loopback del anfitrión.
        String escucha = System.getProperty("intermediario.escucha", "127.0.0.1");
        servidor = HttpServer.create(new InetSocketAddress(escucha, 0), 0);
        servidor.setExecutor(Executors.newCachedThreadPool());
        servidor.createContext("/", this::atender);
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
        corteArmado.set(true);
    }

    public synchronized List<Evento> eventos() {
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
        servidor.stop(0);
    }
}
