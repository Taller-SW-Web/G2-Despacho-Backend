package com.modulodespacho.pruebas.integracion.soporte;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Levanta pruebas/compose.f02.yaml con un nombre de proyecto único: PostgreSQL desechable
 * (tmpfs), Seguridad temporal, Gestión, Operación y Gateway. No lee el .env del desarrollador
 * ni usa volúmenes persistentes. Reserva los puertos 18080 (Gateway) y 18090 (Seguridad).
 */
public final class EntornoCompose implements AutoCloseable {
    public static final String GATEWAY = "http://127.0.0.1:18080";
    public static final String SEGURIDAD = "http://127.0.0.1:18090";
    private static final List<String> SERVICIOS =
            List.of("servicio-gestion-despachos", "servicio-operacion-reparto", "api-gateway");

    private final Path raiz;
    private final Path informes;
    private final List<String> compose;
    private final ClienteApi cliente = new ClienteApi();
    private final String proyecto;
    private final boolean conservar;

    private EntornoCompose(
            Path raiz, Path envVacio, List<Path> adicionales, String proyecto, boolean conservar) {
        this.raiz = raiz;
        this.informes = Path.of("target", "informes");
        this.proyecto = proyecto;
        this.conservar = conservar;
        var comando =
                new ArrayList<>(
                        List.of(
                                "docker", "compose", "--env-file", envVacio.toString(), "-p",
                                proyecto, "-f", raiz.resolve("pruebas/compose.f02.yaml").toString()));
        for (Path adicional : adicionales) comando.addAll(List.of("-f", adicional.toString()));
        this.compose = List.copyOf(comando);
    }

    /** Valida los JAR, levanta los contenedores y espera a que todos respondan. */
    public static EntornoCompose iniciar() throws Exception {
        return iniciar(List.of());
    }

    /**
     * Igual que {@link #iniciar()}, superponiendo archivos Compose propios de un caso (por
     * ejemplo, para interponer un intermediario de prueba entre dos servicios).
     */
    public static EntornoCompose iniciar(List<Path> adicionales) throws Exception {
        return iniciar(
                adicionales, "hito5-it-" + UUID.randomUUID().toString().substring(0, 12), false);
    }

    /**
     * Entorno para rendimiento: nombre fijo y contenedores que siguen activos al cerrar, para que
     * JMeter mida después. Se retira con {@code docker compose -p <proyecto> down}.
     */
    public static EntornoCompose iniciarConservado(String proyecto) throws Exception {
        return iniciar(List.of(), proyecto, true);
    }

    public static EntornoCompose iniciarConservado(String proyecto, List<Path> adicionales) throws Exception {
        return iniciar(adicionales, proyecto, true);
    }

    private static EntornoCompose iniciar(List<Path> adicionales, String proyecto, boolean conservar)
            throws Exception {
        Path raiz = raizBackend();
        for (String servicio : SERVICIOS) {
            Path jar = raiz.resolve(servicio).resolve("target/" + servicio + "-0.0.1-SNAPSHOT.jar");
            if (!Files.isRegularFile(jar))
                throw new IllegalStateException(
                        "Falta " + jar + ". Ejecuta ./mvnw clean verify en " + servicio);
        }
        // Sustituye --env-file /dev/null del verificador Python y funciona también en Windows.
        Path envVacio = Files.createTempFile("hito5-env-", ".env");
        envVacio.toFile().deleteOnExit();
        var entorno = new EntornoCompose(raiz, envVacio, adicionales, proyecto, conservar);
        try {
            entorno.ejecutar(null, "up", "--build", "-d");
            entorno.esperarHttp(SEGURIDAD + "/actuator/health");
            entorno.esperarHttp(GATEWAY + "/actuator/health");
            // El Gateway puede estar sano antes de que Gestión y Operación terminen Flyway.
            entorno.esperarInterno("gestion", 8081);
            entorno.esperarInterno("operacion", 8082);
            return entorno;
        } catch (Exception | AssertionError error) {
            entorno.guardarRegistros();
            entorno.retirar();
            throw error;
        }
    }

    public ClienteApi cliente() {
        return cliente;
    }

    public Path informes() {
        return informes;
    }

    /** Ejecuta SQL de preparación en la base desechable indicada. */
    public void sql(String base, String sentencia) throws Exception {
        ejecutar(
                sentencia,
                "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-U", "prueba", "-d",
                base);
    }

    /** Consulta de solo lectura para la evidencia; devuelve filas separadas por '|'. */
    public List<String> consultar(String base, String sentencia) throws Exception {
        String salida =
                ejecutar(
                        sentencia,
                        "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-At", "-U",
                        "prueba", "-d", base);
        return salida.lines().filter(linea -> !linea.isBlank()).toList();
    }

    /** Solo para escenarios locales: el Bearer viaja por stdin y no aparece en argumentos. */
    public com.fasterxml.jackson.databind.JsonNode postInterno(String ruta, Object cuerpo, String token) throws Exception {
        if (!ruta.matches("/internal/v1/despachos/[0-9a-f-]+/transiciones"))
            throw new IllegalArgumentException("Ruta interna no autorizada para el escenario");
        String configuracion = "url = \"http://gestion:8081" + ruta + "\"\n"
                + "header = \"Authorization: Bearer " + token + "\"\n"
                + "header = \"Idempotency-Key: " + UUID.randomUUID() + "\"\n"
                + "header = \"Content-Type: application/json\"\n"
                + "data = " + ClienteApi.JSON.writeValueAsString(ClienteApi.JSON.writeValueAsString(cuerpo)) + "\n";
        String salida = ejecutar(configuracion, "exec", "-T", "seguridad-local", "curl",
                "--fail", "--silent", "--config", "-");
        return ClienteApi.JSON.readTree(salida);
    }

    /** Conserva los últimos registros de los servicios si la prueba falla. Nunca contienen tokens. */
    public void guardarRegistros() {
        try {
            Files.createDirectories(informes);
            var proceso =
                    new ProcessBuilder(concatenar("logs", "--no-color", "--tail", "200"))
                            .redirectErrorStream(true)
                            .redirectOutput(informes.resolve("servicios-compose-" + proyecto + ".log").toFile())
                            .start();
            proceso.waitFor(60, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignorado) {
            // Los registros solo ayudan a diagnosticar; no deben ocultar el fallo original.
        }
    }

    public String proyecto() {
        return proyecto;
    }

    public String direccionPublicada(String servicio, int puerto) throws Exception {
        if (!"intermediario".equals(servicio) || puerto != 19099)
            throw new IllegalArgumentException("Solo el control del intermediario se publica");
        String direccion = ejecutar(null,"port",servicio,Integer.toString(puerto)).strip();
        if (!direccion.matches("127\\.0\\.0\\.1:[0-9]+"))
            throw new IllegalStateException("El control debe estar publicado solo en loopback");
        return "http://" + direccion;
    }

    @Override
    public void close() {
        if (!conservar) retirar();
    }

    private void retirar() {
        try {
            ejecutar(null, "down", "--remove-orphans", "--volumes");
        } catch (Exception error) {
            System.err.println("No se pudo retirar el entorno Compose: " + error.getMessage());
        }
    }

    private void esperarHttp(String url) throws InterruptedException {
        Instant limite = Instant.now().plus(Duration.ofMinutes(3));
        while (Instant.now().isBefore(limite)) {
            try {
                if (cliente.get(url, null).estado() == 200) return;
            } catch (RuntimeException servicioAunNoDisponible) {
                // Reintentar hasta el tiempo máximo.
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("El servicio de pruebas no inició: " + url);
    }

    private void esperarInterno(String servicio, int puerto) throws Exception {
        Instant limite = Instant.now().plus(Duration.ofMinutes(3));
        while (Instant.now().isBefore(limite)) {
            var proceso =
                    new ProcessBuilder(
                                    concatenar(
                                            "exec", "-T", "seguridad-local", "curl", "--fail",
                                            "--silent", "http://" + servicio + ":" + puerto
                                                    + "/actuator/health"))
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
            if (proceso.waitFor(30, TimeUnit.SECONDS) && proceso.exitValue() == 0) return;
            Thread.sleep(2000);
        }
        throw new IllegalStateException("No inició " + servicio);
    }

    private String ejecutar(String entrada, String... argumentos) throws Exception {
        var constructor = new ProcessBuilder(concatenar(argumentos)).directory(raiz.toFile());
        constructor.redirectErrorStream(true);
        Process proceso = constructor.start();
        // Cerrar la entrada estándar evita que docker compose espere datos interactivos.
        try (var escritura = proceso.getOutputStream()) {
            if (entrada != null) escritura.write(entrada.getBytes(StandardCharsets.UTF_8));
        }
        // Consumir la salida evita bloquear el proceso; se muestra solo si falla.
        String salida = new String(proceso.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!proceso.waitFor(10, TimeUnit.MINUTES) || proceso.exitValue() != 0)
            throw new IllegalStateException(
                    "Falló docker compose " + String.join(" ", argumentos) + ":\n" + recortar(salida));
        return salida;
    }

    private List<String> concatenar(String... argumentos) {
        var comando = new ArrayList<>(compose);
        comando.addAll(List.of(argumentos));
        return comando;
    }

    private static String recortar(String texto) {
        return texto.length() <= 4000 ? texto : texto.substring(texto.length() - 4000);
    }

    /** Ubica la raíz del backend desde pruebas/integracion-java o desde una propiedad explícita. */
    private static Path raizBackend() {
        String explicita = System.getProperty("backend.raiz");
        Path candidata =
                explicita != null && !explicita.isBlank()
                        ? Path.of(explicita)
                        : Path.of("").toAbsolutePath().getParent().getParent();
        if (!Files.isRegularFile(candidata.resolve("pruebas/compose.f02.yaml")))
            throw new IllegalStateException(
                    "No se encontró pruebas/compose.f02.yaml desde " + candidata
                            + ". Ejecuta desde pruebas/integracion-java o usa -Dbackend.raiz=...");
        return candidata.normalize();
    }
}
