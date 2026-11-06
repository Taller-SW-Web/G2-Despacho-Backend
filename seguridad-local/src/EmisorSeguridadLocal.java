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
            // Las rutas internas de Despacho no usan scopes: modulo-despacho se identifica solo por su token de servicio.
            "modulo-despacho", List.of());
    private static final Map<String, Perfil> PERFILES = Map.of(
            "gestor", new Perfil("11111111-1111-4111-8111-111111111111", List.of("GESTOR_DESPACHO")),
            "repartidor", new Perfil("22222222-2222-4222-8222-222222222222", List.of()),
            "repartidor-kevin", new Perfil("44444444-4444-4444-8444-444444444444", List.of()),
            "sin-rol", new Perfil("33333333-3333-4333-8333-333333333333", List.of()));

    /** Cuentas ficticias del login propuesto; Seguridad y Usuarios entregará las cuentas reales. */
    static final String CLAVE_USUARIO_PRUEBA = "Ruta2026!";
    private static final Map<String, String> USUARIOS = Map.of(
            "repartidor.prueba", "repartidor",
            "mrojas@despacho.pe", "repartidor",
            "gerardo@despacho.pe", "repartidor",
            "kevin@despacho.pe", "repartidor-kevin",
            "gestor@despacho.pe", "gestor",
            "gestor.prueba", "gestor");

    private final HttpServer servidor;
    private final ExecutorService ejecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final KeyPair claves;
    private final String identificadorClave = "g2-local-" + UUID.randomUUID();
    private final String emisor;
    private final Map<String, Object> jwks;
    // Estado efímero del doble local. Reiniciar el contenedor invalida todas sus sesiones.
    private final Map<String, DesafioLocal> desafios = new LinkedHashMap<>();
    private final Map<String, SesionLocal> sesiones = new LinkedHashMap<>();
    private final Map<String, RefrescoLocal> refrescos = new LinkedHashMap<>();
    private final Map<String, AccesoLocal> accesos = new LinkedHashMap<>();
    private final Map<String, java.util.ArrayList<Long>> solicitudesOtp = new LinkedHashMap<>();
    // Buzón simulado, consultable solo por la herramienta local; nunca se registra en logs.
    private final Map<String, String> buzonOtp = new LinkedHashMap<>();
    private final java.security.SecureRandom aleatorio = new java.security.SecureRandom();

    private static final class DesafioLocal {
        final String correo;
        final long venceEn = Instant.now().getEpochSecond() + 300;
        long codigoVenceEn;
        int intentos;
        byte[] hashCodigo;
        DesafioLocal(String correo) { this.correo = correo; }
    }
    private record SesionLocal(String correo, Perfil perfil, long venceEn) {}
    private static final class RefrescoLocal {
        final String familia;
        final long venceEn;
        boolean usado;
        RefrescoLocal(String familia, long venceEn) { this.familia = familia; this.venceEn = venceEn; }
    }
    private record AccesoLocal(String familia, long venceEn) {}

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
                case PREFIJO + "/otp/solicitar" -> solicitarOtp(intercambio);
                case PREFIJO + "/otp/verificar" -> verificarOtp(intercambio);
                case PREFIJO + "/refresh" -> renovarSesion(intercambio);
                case PREFIJO + "/logout" -> cerrarSesion(intercambio);
                case PREFIJO + "/me" -> consultarPerfil(intercambio);
                case "/pruebas/token-usuario" -> emitirUsuario(intercambio);
                case "/pruebas/codigo-otp" -> consultarCodigoLocal(intercambio);
                default -> throw new SolicitudInvalida(404, "NO_ENCONTRADO", "Ruta de prueba inexistente");
            }
        } catch (SolicitudInvalida error) {
            Map<String, Object> problema = new LinkedHashMap<>(Map.of(
                    "type", "about:blank", "title", "Solicitud de prueba no procesada",
                    "status", error.estado, "code", error.codigo, "detail", error.getMessage()));
            if (error.intentosRestantes != null) problema.put("intentosRestantes", error.intentosRestantes);
            responder(intercambio, error.estado, problema);
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
        // Reproduce el token técnico actual de G7: no concede una audiencia interna a G2.
        claims.put("aud", "modulo-despacho".equals(cliente)
                ? List.of("api-productos", "api-seguridad", "api-ventas") : List.of("api-despacho"));
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
                    "Utilizar perfil=gestor, repartidor, repartidor-kevin o sin-rol");
        }
        Map<String, Object> claims = claims(perfil.sujeto(), "acceso", 900);
        claims.put("roles", perfil.roles());
        claims.put("permisos", List.of());
        responder(intercambio, 200, Map.of(
                "accessToken", firmar(claims), "tokenType", "Bearer", "expiresIn", 900,
                "entorno", "simulado-local"));
    }

    /** Doble del contrato G7 v1; el formulario antiguo se conserva solo para herramientas locales. */
    private synchronized void iniciarSesion(HttpExchange intercambio) throws IOException, GeneralSecurityException {
        depurarSesiones();
        String tipo = intercambio.getRequestHeaders().getFirst("Content-Type");
        boolean esJson = tipo != null && tipo.split(";", 2)[0].trim().equalsIgnoreCase("application/json");
        Map<String, String> formulario = esJson ? cuerpoJson(intercambio) : formulario(intercambio);
        String usuario = formulario.getOrDefault(esJson ? "correo" : "usuario", "").trim().toLowerCase(java.util.Locale.ROOT);
        String perfil = USUARIOS.get(usuario);
        if (perfil == null || !CLAVE_USUARIO_PRUEBA.equals(formulario.get(esJson ? "contrasena" : "clave"))) {
            throw new SolicitudInvalida(401, "CREDENCIALES_INVALIDAS", "Usuario o contraseña incorrectos");
        }
        Perfil datos = PERFILES.get(perfil);
        if (desafios.size() + sesiones.size() >= 2000) {
            throw new SolicitudInvalida(429, "DEMASIADAS_SOLICITUDES", "Reiniciar el emisor local para liberar sesiones de prueba");
        }
        if (datos.roles().contains("GESTOR_DESPACHO")) {
            String desafio = "chg_" + UUID.randomUUID();
            desafios.put(desafio, new DesafioLocal(usuario));
            responder(intercambio, 200, Map.of("mfaRequerido", true, "challengeToken", desafio,
                    "canal", "EMAIL", "expiraEn", 300));
        } else {
            responder(intercambio, 200, nuevaSesion(usuario, datos));
        }
    }

    private synchronized void solicitarOtp(HttpExchange intercambio) throws IOException {
        Map<String, String> cuerpo = cuerpoJson(intercambio);
        DesafioLocal desafio = exigirDesafio(cuerpo);
        if (!"EMAIL".equals(cuerpo.getOrDefault("canal", "EMAIL"))) {
            throw new SolicitudInvalida(400, "VALIDACION", "El doble local solo simula EMAIL");
        }
        long ahora = Instant.now().getEpochSecond();
        var solicitudes = solicitudesOtp.computeIfAbsent(desafio.correo, clave -> new java.util.ArrayList<>());
        solicitudes.removeIf(instante -> instante <= ahora - 900);
        if (solicitudes.size() >= 3) {
            intercambio.getResponseHeaders().set("Retry-After", Long.toString(Math.max(1, solicitudes.getFirst() + 900 - ahora)));
            throw new SolicitudInvalida(429, "DEMASIADAS_SOLICITUDES", "Máximo tres códigos por usuario cada quince minutos");
        }
        solicitudes.add(ahora);
        String anterior = buzonOtp.get(cuerpo.get("challengeToken"));
        String codigo;
        do { codigo = String.format(java.util.Locale.ROOT, "%06d", aleatorio.nextInt(1_000_000)); } while (codigo.equals(anterior));
        desafio.hashCodigo = hashCodigo(codigo);
        buzonOtp.put(cuerpo.get("challengeToken"), codigo);
        desafio.codigoVenceEn = ahora + 300;
        desafio.intentos = 3;
        responder(intercambio, 202, Map.of("canal", "EMAIL", "destinoEnmascarado", "g****r@despacho.pe",
                "expiraEn", 300, "desafioExpiraEn", desafio.venceEn - ahora));
    }

    private synchronized void verificarOtp(HttpExchange intercambio) throws IOException, GeneralSecurityException {
        Map<String, String> cuerpo = cuerpoJson(intercambio);
        DesafioLocal desafio = exigirDesafio(cuerpo);
        String codigo = requerido(cuerpo, "codigo");
        if (!codigo.matches("[0-9]{6}")) throw new SolicitudInvalida(400, "VALIDACION", "El código debe tener seis dígitos");
        if (desafio.codigoVenceEn <= Instant.now().getEpochSecond()) {
            throw new SolicitudInvalida(410, "OTP_EXPIRADO", "Solicitar un código nuevo");
        }
        if (desafio.intentos <= 0) throw new SolicitudInvalida(401, "OTP_INTENTOS_AGOTADOS", "Solicitar un código nuevo", 0);
        if (!java.security.MessageDigest.isEqual(desafio.hashCodigo, hashCodigo(codigo))) {
            desafio.intentos--;
            throw new SolicitudInvalida(401, desafio.intentos == 0 ? "OTP_INTENTOS_AGOTADOS" : "CODIGO_INVALIDO",
                    "Código local incorrecto", desafio.intentos);
        }
        desafios.remove(cuerpo.get("challengeToken"));
        buzonOtp.remove(cuerpo.get("challengeToken"));
        responder(intercambio, 200, nuevaSesion(desafio.correo, PERFILES.get(USUARIOS.get(desafio.correo))));
    }

    private synchronized void consultarCodigoLocal(HttpExchange intercambio) throws IOException {
        Map<String, String> cuerpo = cuerpoJson(intercambio);
        DesafioLocal desafio = exigirDesafio(cuerpo);
        String codigo = buzonOtp.get(cuerpo.get("challengeToken"));
        if (codigo == null || desafio.codigoVenceEn <= Instant.now().getEpochSecond()) {
            throw new SolicitudInvalida(404, "NO_ENCONTRADO", "Solicitar primero un código local");
        }
        responder(intercambio, 200, Map.of("codigo", codigo, "entorno", "simulado-local"));
    }

    private static byte[] hashCodigo(String codigo) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(codigo.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private DesafioLocal exigirDesafio(Map<String, String> cuerpo) {
        String token = requerido(cuerpo, "challengeToken");
        DesafioLocal desafio = desafios.get(token);
        if (desafio == null || desafio.venceEn <= Instant.now().getEpochSecond()) {
            desafios.remove(token);
            throw new SolicitudInvalida(401, "TOKEN_INVALIDO", "Desafío inválido o vencido");
        }
        return desafio;
    }

    private Map<String, Object> nuevaSesion(String correo, Perfil perfil) throws GeneralSecurityException {
        String familia = UUID.randomUUID().toString();
        var sesion = new SesionLocal(correo, perfil, Instant.now().getEpochSecond() + 7 * 86400);
        sesiones.put(familia, sesion);
        return emitirSesion(familia, sesion);
    }

    private Map<String, Object> emitirSesion(String familia, SesionLocal sesion) throws GeneralSecurityException {
        Perfil datos = sesion.perfil();
        Map<String, Object> claims = claims(datos.sujeto(), "acceso", 900);
        claims.put("roles", datos.roles());
        claims.put("permisos", List.of());
        claims.put("preferred_username", sesion.correo());
        String acceso = firmar(claims);
        String refresco = UUID.randomUUID().toString();
        accesos.put(acceso, new AccesoLocal(familia, Instant.now().getEpochSecond() + 900));
        refrescos.put(refresco, new RefrescoLocal(familia, sesion.venceEn()));
        return Map.of("accessToken", acceso, "refreshToken", refresco, "tokenType", "Bearer", "expiresIn", 900,
                "usuario", usuario(sesion), "entorno", "simulado-local");
    }

    private Map<String, Object> usuario(SesionLocal sesion) {
        String nombre = sesion.perfil().roles().contains("GESTOR_DESPACHO") ? "Gestor de prueba"
                : sesion.perfil().sujeto().startsWith("4444") ? "Kevin de prueba" : "Gerardo de prueba";
        return Map.of("id", sesion.perfil().sujeto(), "nombreCompleto", nombre, "correo", sesion.correo(), "roles", sesion.perfil().roles());
    }

    private synchronized void renovarSesion(HttpExchange intercambio) throws IOException, GeneralSecurityException {
        String token = requerido(cuerpoJson(intercambio), "refreshToken");
        depurarSesiones();
        RefrescoLocal refresco = refrescos.get(token);
        if (refresco == null || refresco.venceEn <= Instant.now().getEpochSecond() || !sesiones.containsKey(refresco.familia)) {
            throw new SolicitudInvalida(401, "REFRESCO_INVALIDO", "Iniciar sesión nuevamente");
        }
        if (refresco.usado) {
            sesiones.remove(refresco.familia);
            throw new SolicitudInvalida(401, "REFRESCO_INVALIDO", "Familia de sesión revocada por reutilización");
        }
        Map<String, Object> respuesta = emitirSesion(refresco.familia, sesiones.get(refresco.familia));
        refresco.usado = true;
        responder(intercambio, 200, respuesta);
    }

    private synchronized void cerrarSesion(HttpExchange intercambio) throws IOException {
        String token = requerido(cuerpoJson(intercambio), "refreshToken");
        RefrescoLocal refresco = refrescos.get(token);
        if (refresco != null) sesiones.remove(refresco.familia);
        intercambio.getResponseHeaders().set("Cache-Control", "no-store");
        intercambio.sendResponseHeaders(204, -1);
    }

    private synchronized void consultarPerfil(HttpExchange intercambio) throws IOException {
        exigirMetodo(intercambio, "GET");
        String cabecera = intercambio.getRequestHeaders().getFirst("Authorization");
        AccesoLocal acceso = cabecera != null && cabecera.startsWith("Bearer ") ? accesos.get(cabecera.substring(7)) : null;
        SesionLocal sesion = acceso == null ? null : sesiones.get(acceso.familia());
        if (sesion == null || acceso.venceEn() <= Instant.now().getEpochSecond()) {
            throw new SolicitudInvalida(401, "TOKEN_INVALIDO", "Token local no aceptado");
        }
        Map<String, Object> perfil = new LinkedHashMap<>(usuario(sesion));
        perfil.putAll(Map.of("nombres", perfil.get("nombreCompleto"), "apellidos", "Local", "estado", "ACTIVO",
                "correoVerificado", true, "mfaHabilitado", sesion.perfil().roles().contains("GESTOR_DESPACHO")));
        perfil.put("celular", null);
        perfil.put("fechaNacimiento", null);
        responder(intercambio, 200, perfil);
    }

    private void depurarSesiones() {
        long ahora = Instant.now().getEpochSecond();
        desafios.values().removeIf(desafio -> desafio.venceEn <= ahora);
        buzonOtp.keySet().removeIf(token -> !desafios.containsKey(token));
        sesiones.values().removeIf(sesion -> sesion.venceEn() <= ahora);
        refrescos.values().removeIf(refresco -> refresco.venceEn <= ahora);
        accesos.values().removeIf(acceso -> acceso.venceEn() <= ahora);
    }

    private static String requerido(Map<String, String> cuerpo, String campo) {
        String valor = cuerpo.get(campo);
        if (valor == null || valor.isBlank()) throw new SolicitudInvalida(400, "VALIDACION", "Falta el campo " + campo);
        return valor;
    }

    /** Lector acotado: estos cuerpos del contrato solo admiten objetos planos de cadenas. */
    private static Map<String, String> cuerpoJson(HttpExchange intercambio) throws IOException {
        exigirMetodo(intercambio, "POST");
        String tipo = intercambio.getRequestHeaders().getFirst("Content-Type");
        if (tipo == null || !tipo.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new SolicitudInvalida(415, "FORMATO_INVALIDO", "Utilizar application/json");
        }
        byte[] bytes = intercambio.getRequestBody().readNBytes(MAXIMO_CUERPO + 1);
        if (bytes.length > MAXIMO_CUERPO) throw new SolicitudInvalida(413, "SOLICITUD_EXCESIVA", "Cuerpo demasiado grande");
        return new LectorJson(new String(bytes, StandardCharsets.UTF_8)).leer();
    }

    private static final class LectorJson {
        private final String texto;
        private int posicion;
        LectorJson(String texto) { this.texto = texto; }
        private void espacios() { while (posicion < texto.length() && " \r\n\t".indexOf(texto.charAt(posicion)) >= 0) posicion++; }
        private boolean consumir(char caracter) {
            espacios();
            if (posicion < texto.length() && texto.charAt(posicion) == caracter) { posicion++; return true; }
            return false;
        }
        private void exigir(char caracter) { if (!consumir(caracter)) throw new IllegalArgumentException("JSON inválido"); }
        Map<String, String> leer() {
            var campos = new LinkedHashMap<String, String>();
            exigir('{');
            if (!consumir('}')) {
                do {
                    String nombre = cadena();
                    exigir(':');
                    if (campos.putIfAbsent(nombre, cadena()) != null) throw new IllegalArgumentException("Campo duplicado");
                } while (consumir(','));
                exigir('}');
            }
            espacios();
            if (posicion != texto.length()) throw new IllegalArgumentException("Contenido adicional");
            return campos;
        }
        private String cadena() {
            exigir('"');
            var valor = new StringBuilder();
            while (posicion < texto.length()) {
                char caracter = texto.charAt(posicion++);
                if (caracter == '"') return valor.toString();
                if (caracter < 32) throw new IllegalArgumentException("Control inválido");
                if (caracter == '\\') {
                    if (posicion >= texto.length()) throw new IllegalArgumentException("Escape incompleto");
                    char escape = texto.charAt(posicion++);
                    caracter = switch (escape) {
                        case '"', '\\', '/' -> escape;
                        case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                        case 'u' -> {
                            if (posicion + 4 > texto.length()) throw new IllegalArgumentException("Unicode incompleto");
                            char unicode = (char) Integer.parseInt(texto.substring(posicion, posicion + 4), 16);
                            posicion += 4;
                            yield unicode;
                        }
                        default -> throw new IllegalArgumentException("Escape inválido");
                    };
                }
                valor.append(caracter);
            }
            throw new IllegalArgumentException("Cadena incompleta");
        }
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
        private final Integer intentosRestantes;

        private SolicitudInvalida(int estado, String codigo, String mensaje) {
            this(estado, codigo, mensaje, null);
        }

        private SolicitudInvalida(int estado, String codigo, String mensaje, Integer intentosRestantes) {
            super(mensaje);
            this.estado = estado;
            this.codigo = codigo;
            this.intentosRestantes = intentosRestantes;
        }
    }
}
