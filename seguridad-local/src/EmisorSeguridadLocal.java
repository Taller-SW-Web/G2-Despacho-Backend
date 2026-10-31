import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Emisor temporal de G2 para integración local. No implementa Seguridad de G7.
 * Las claves se generan en memoria en cada arranque y nunca se escriben en disco.
 */
public final class EmisorSeguridadLocal implements AutoCloseable {

    static final String SECRETO_PRUEBA = "despacho-local-no-produccion";
    private static final String PREFIJO = "/api/v1/auth";
    private static final int MAXIMO_CUERPO = 4096;
    private static final Map<String, List<String>> CLIENTES = Map.of(
            "modulo-marketplace", List.of("cotizaciones:calcular", "seguimientos:leer"),
            "modulo-chatbot", List.of("cotizaciones:calcular", "seguimientos:leer"),
            "modulo-ventas", List.of("despachos:crear", "despachos:cancelar", "seguimientos:leer"),
            "modulo-despacho", List.of("operacion:incidencias:leer", "operacion:capacidad:reservar",
                    "operacion:capacidad:liberar", "operacion:proyecciones:escribir",
                    "gestion:despachos:transicionar", "gestion:catalogos:leer"));
    private static final Map<String, Perfil> PERFILES = Map.of(
            "gestor", new Perfil("11111111-1111-4111-8111-111111111111", List.of("GESTOR_DESPACHO")),
            "repartidor", new Perfil("22222222-2222-4222-8222-222222222222", List.of()),
            "sin-rol", new Perfil("33333333-3333-4333-8333-333333333333", List.of()));

    /** Cuentas ficticias del login propuesto; Seguridad y Usuarios entregará las cuentas reales. */
    static final String CLAVE_USUARIO_PRUEBA = "Ruta2026!";
    private static final Map<String, String> USUARIOS = Map.of(
            "repartidor.prueba", "repartidor",
            "mrojas@despacho.pe", "repartidor",
            "gestor.prueba", "gestor");

    private final HttpServer servidor;
    private final ExecutorService ejecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final KeyPair claves;
    private final String identificadorClave = "g2-local-" + UUID.randomUUID();
    private final String emisor;
    private final Map<String, Object> jwks;

    private record Perfil(String sujeto, List<String> roles) {}

    public EmisorSeguridadLocal(String direccion, int puerto, String origen, boolean habilitado)
            throws IOException, GeneralSecurityException {
        if (!habilitado) {
            ejecutor.shutdownNow();
            throw new IllegalStateException(
                    "Herramienta temporal: requiere HABILITAR_SEGURIDAD_LOCAL=true de forma explícita");
        }
        emisor = origen.replaceAll("/+$", "") + PREFIJO;
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        claves = generador.generateKeyPair();
        RSAPublicKey publica = (RSAPublicKey) claves.getPublic();
        jwks = Map.of("keys", List.of(Map.of(
                "kty", "RSA", "use", "sig", "alg", "RS256", "kid", identificadorClave,
                "n", enteroBase64(publica.getModulus()), "e", enteroBase64(publica.getPublicExponent()))));
        servidor = HttpServer.create(new InetSocketAddress(direccion, puerto), 0);
        servidor.setExecutor(ejecutor);
        servidor.createContext("/", this::atender);
    }

    public static void main(String[] argumentos) throws Exception {
        boolean habilitado = "true".equals(System.getenv("HABILITAR_SEGURIDAD_LOCAL"));
        int puerto = Integer.parseInt(System.getenv().getOrDefault("PORT", "8090"));
        String direccion = System.getenv().getOrDefault("DIRECCION_ESCUCHA", "127.0.0.1");
        var servidor = new EmisorSeguridadLocal(
                direccion, puerto, "http://seguridad-local:8090", habilitado);
        Runtime.getRuntime().addShutdownHook(new Thread(servidor::close));
        servidor.iniciar();
        System.out.println("G2: EMISOR LOCAL TEMPORAL. No es Seguridad real ni es apto para producción.");
        System.out.println("Claves efímeras: al reiniciar, obtener tokens nuevos y reiniciar consumidores.");
    }

    public void iniciar() {
        servidor.start();
    }

    public int puerto() {
        return servidor.getAddress().getPort();
    }

    @Override
    public void close() {
        servidor.stop(0);
        ejecutor.shutdownNow();
    }

    private void atender(HttpExchange intercambio) throws IOException {
        try {
            String ruta = intercambio.getRequestURI().getPath();
            switch (ruta) {
                case "/actuator/health" -> {
                    exigirMetodo(intercambio, "GET");
                    responder(intercambio, 200, Map.of("status", "UP", "entorno", "simulado-local"));
                }
                case PREFIJO + "/.well-known/openid-configuration" -> {
                    exigirMetodo(intercambio, "GET");
                    responder(intercambio, 200, Map.of(
                            "issuer", emisor, "jwks_uri", emisor + "/.well-known/jwks.json",
                            "token_endpoint", emisor + "/token",
                            "id_token_signing_alg_values_supported", List.of("RS256"),
                            "grant_types_supported", List.of("client_credentials"),
                            "entorno", "simulado-local"));
                }
                case PREFIJO + "/.well-known/jwks.json" -> {
                    exigirMetodo(intercambio, "GET");
                    responder(intercambio, 200, jwks);
                }
                case PREFIJO + "/token" -> emitirServicio(intercambio);
                case PREFIJO + "/login" -> iniciarSesion(intercambio);
                case "/pruebas/token-usuario" -> emitirUsuario(intercambio);
                default -> throw new SolicitudInvalida(404, "NO_ENCONTRADO", "Ruta de prueba inexistente");
            }
        } catch (SolicitudInvalida error) {
            responder(intercambio, error.estado, Map.of(
                    "type", "about:blank", "title", "Solicitud de prueba no procesada",
                    "status", error.estado, "code", error.codigo, "detail", error.getMessage()));
        } catch (GeneralSecurityException error) {
            responder(intercambio, 500, Map.of("code", "ERROR_FIRMA_LOCAL",
                    "detail", "No fue posible generar el token de prueba"));
        } catch (IllegalArgumentException error) {
            responder(intercambio, 400, Map.of("code", "SOLICITUD_INVALIDA",
                    "detail", "El formulario de prueba no es válido"));
        } finally {
            intercambio.close();
        }
    }

    private void emitirServicio(HttpExchange intercambio) throws IOException, GeneralSecurityException {
        Map<String, String> formulario = formulario(intercambio);
        if (!"client_credentials".equals(formulario.get("grant_type"))) {
            throw new SolicitudInvalida(400, "SOLICITUD_INVALIDA", "Utilizar grant_type=client_credentials");
        }
        String cliente = formulario.get("client_id");
        List<String> permitidos = cliente == null ? null : CLIENTES.get(cliente);
        if (permitidos == null || !SECRETO_PRUEBA.equals(formulario.get("client_secret"))) {
            throw new SolicitudInvalida(401, "CLIENTE_INVALIDO", "Cliente de prueba no aceptado");
        }
        String scope = formulario.getOrDefault("scope", "").trim();
        List<String> solicitados = scope.isEmpty() ? permitidos
                : List.copyOf(new LinkedHashSet<>(Arrays.asList(scope.split("\\s+"))));
        if (!permitidos.containsAll(solicitados)) {
            throw new SolicitudInvalida(403, "SCOPE_INSUFICIENTE",
                    "El cliente local no tiene esos scopes de prueba");
        }
        Map<String, Object> claims = claims(cliente, "servicio", 3600);
        claims.put("aud", List.of("api-despacho"));
        claims.put("scope", String.join(" ", solicitados));
        responder(intercambio, 200, Map.of(
                "accessToken", firmar(claims), "tokenType", "Bearer", "expiresIn", 3600,
                "scopes", solicitados, "entorno", "simulado-local"));
    }

    private void emitirUsuario(HttpExchange intercambio) throws IOException, GeneralSecurityException {
        Map<String, String> formulario = formulario(intercambio);
        Perfil perfil = PERFILES.get(formulario.getOrDefault("perfil", ""));
        if (perfil == null) {
            throw new SolicitudInvalida(400, "PERFIL_INVALIDO",
                    "Utilizar perfil=gestor, repartidor o sin-rol");
        }
        Map<String, Object> claims = claims(perfil.sujeto(), "acceso", 900);
        claims.put("roles", perfil.roles());
        claims.put("permisos", List.of());
        responder(intercambio, 200, Map.of(
                "accessToken", firmar(claims), "tokenType", "Bearer", "expiresIn", 900,
                "entorno", "simulado-local"));
    }

    /** Contrato propuesto a Seguridad: formulario usuario/clave y JWT de acceso con la misma forma que el real. */
    private void iniciarSesion(HttpExchange intercambio) throws IOException, GeneralSecurityException {
        Map<String, String> formulario = formulario(intercambio);
        String usuario = formulario.getOrDefault("usuario", "").trim().toLowerCase(java.util.Locale.ROOT);
        String perfil = USUARIOS.get(usuario);
        if (perfil == null || !CLAVE_USUARIO_PRUEBA.equals(formulario.get("clave"))) {
            throw new SolicitudInvalida(401, "CREDENCIALES_INVALIDAS", "Usuario o contraseña incorrectos");
        }
        Perfil datos = PERFILES.get(perfil);
        Map<String, Object> claims = claims(datos.sujeto(), "acceso", 900);
        claims.put("roles", datos.roles());
        claims.put("permisos", List.of());
        claims.put("preferred_username", usuario);
        responder(intercambio, 200, Map.of(
                "accessToken", firmar(claims), "tokenType", "Bearer", "expiresIn", 900,
                "entorno", "simulado-local"));
    }

    private Map<String, Object> claims(String sujeto, String tipo, int duracion) {
        long ahora = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", emisor);
        claims.put("sub", sujeto);
        claims.put("tipo", tipo);
        claims.put("iat", ahora);
        claims.put("exp", ahora + duracion);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("entorno", "simulado-local");
        return claims;
    }

    private String firmar(Map<String, Object> claims) throws GeneralSecurityException {
        String cabecera = base64(json(Map.of(
                "alg", "RS256", "typ", "JWT", "kid", identificadorClave)).getBytes(StandardCharsets.UTF_8));
        String contenido = cabecera + "." + base64(json(claims).getBytes(StandardCharsets.UTF_8));
        Signature firma = Signature.getInstance("SHA256withRSA");
        firma.initSign(claves.getPrivate());
        firma.update(contenido.getBytes(StandardCharsets.US_ASCII));
        return contenido + "." + base64(firma.sign());
    }

    private static Map<String, String> formulario(HttpExchange intercambio) throws IOException {
        exigirMetodo(intercambio, "POST");
        String tipo = intercambio.getRequestHeaders().getFirst("Content-Type");
        if (tipo == null || !tipo.split(";", 2)[0].trim()
                .equalsIgnoreCase("application/x-www-form-urlencoded")) {
            throw new SolicitudInvalida(415, "FORMATO_INVALIDO",
                    "Utilizar application/x-www-form-urlencoded");
        }
        byte[] cuerpo = intercambio.getRequestBody().readNBytes(MAXIMO_CUERPO + 1);
        if (cuerpo.length > MAXIMO_CUERPO) {
            throw new SolicitudInvalida(413, "SOLICITUD_EXCESIVA", "Formulario de prueba demasiado grande");
        }
        Map<String, String> campos = new LinkedHashMap<>();
        for (String campo : new String(cuerpo, StandardCharsets.UTF_8).split("&")) {
            if (campo.isEmpty()) {
                continue;
            }
            String[] partes = campo.split("=", 2);
            String nombre = URLDecoder.decode(partes[0], StandardCharsets.UTF_8);
            String valor = URLDecoder.decode(partes.length == 2 ? partes[1] : "", StandardCharsets.UTF_8);
            if (campos.putIfAbsent(nombre, valor) != null) {
                throw new SolicitudInvalida(400, "SOLICITUD_INVALIDA", "No repetir campos del formulario");
            }
        }
        return campos;
    }

    private static void exigirMetodo(HttpExchange intercambio, String metodo) {
        if (!metodo.equals(intercambio.getRequestMethod())) {
            intercambio.getResponseHeaders().set("Allow", metodo);
            throw new SolicitudInvalida(405, "METODO_INVALIDO", "Método HTTP no permitido");
        }
    }

    private static void responder(HttpExchange intercambio, int estado, Map<String, ?> contenido)
            throws IOException {
        byte[] cuerpo = json(contenido).getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type",
                estado >= 400 ? "application/problem+json; charset=UTF-8" : "application/json; charset=UTF-8");
        intercambio.getResponseHeaders().set("Cache-Control", "no-store");
        intercambio.getResponseHeaders().set("X-Entorno-Seguridad", "simulado-local");
        intercambio.sendResponseHeaders(estado, cuerpo.length);
        intercambio.getResponseBody().write(cuerpo);
    }

    private static String enteroBase64(BigInteger numero) {
        byte[] bytes = numero.toByteArray();
        return base64(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Serializa únicamente los valores controlados por esta herramienta; no interpreta JSON recibido.
    private static String json(Object valor) {
        if (valor == null) {
            return "null";
        }
        if (valor instanceof String texto) {
            StringBuilder salida = new StringBuilder("\"");
            for (int indice = 0; indice < texto.length(); indice++) {
                char caracter = texto.charAt(indice);
                switch (caracter) {
                    case '"' -> salida.append("\\\"");
                    case '\\' -> salida.append("\\\\");
                    case '\n' -> salida.append("\\n");
                    case '\r' -> salida.append("\\r");
                    case '\t' -> salida.append("\\t");
                    default -> {
                        if (caracter < 0x20) {
                            salida.append(String.format("\\u%04x", (int) caracter));
                        } else {
                            salida.append(caracter);
                        }
                    }
                }
            }
            return salida.append('"').toString();
        }
        if (valor instanceof Number || valor instanceof Boolean) {
            return valor.toString();
        }
        if (valor instanceof Map<?, ?> mapa) {
            return mapa.entrySet().stream().map(entrada ->
                    json(entrada.getKey().toString()) + ":" + json(entrada.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (valor instanceof List<?> lista) {
            return lista.stream().map(EmisorSeguridadLocal::json).collect(Collectors.joining(",", "[", "]"));
        }
        throw new IllegalArgumentException("Tipo JSON no admitido en la herramienta local");
    }

    private static final class SolicitudInvalida extends RuntimeException {
        private final int estado;
        private final String codigo;

        private SolicitudInvalida(int estado, String codigo, String mensaje) {
            super(mensaje);
            this.estado = estado;
            this.codigo = codigo;
        }
    }
}
