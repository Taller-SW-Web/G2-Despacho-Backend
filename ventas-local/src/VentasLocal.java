import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Doble local de Ventas y Postventa (G4) para probar el webhook de Despacho (§7.3 y §7.4 del
 * contrato externo). No implementa Ventas: solo valida el token de servicio, deduplica por
 * idEvento, evita retrocesos del estado comercial y expone lo recibido para verificarlo.
 * Todo el estado vive en memoria y se pierde al reiniciar.
 */
public final class VentasLocal implements AutoCloseable {

    static final String RUTA_EVENTOS = "/api/v1/integraciones/despachos/eventos";
    static final String AUDIENCIA = "api-ventas";
    static final String SCOPE = "ventas:pedidos:estado";
    static final String EMISOR_PERMITIDO = "modulo-despacho";
    private static final int MAXIMO_CUERPO = 65_536;
    private static final int MAXIMO_FALLOS = 20;
    private static final Duration ESPERA_RECARGA_CLAVES = Duration.ofSeconds(10);

    /** Efecto comercial acordado en §7.3; los tipos sin efecto solo registran el hito. */
    private static final Map<String, String> EFECTOS =
            Map.of(
                    "DESPACHO_EN_CAMINO", "DESPACHADO",
                    "DESPACHO_ENTREGADO", "ENTREGADO",
                    "DESPACHO_DEVUELTO_A_ORIGEN", "ANULADO",
                    "DESPACHO_CANCELADO", "ANULADO");

    private static final Set<String> TIPOS =
            Set.of(
                    "DESPACHO_ASIGNADO",
                    "DESPACHO_EN_CAMINO",
                    "DESPACHO_ENTREGADO",
                    "DESPACHO_FALLIDO",
                    "DESPACHO_REPROGRAMADO",
                    "DESPACHO_DEVUELTO_A_ORIGEN",
                    "DESPACHO_CANCELADO");

    /** Orden del estado comercial: un evento atrasado nunca lo hace retroceder. */
    private static final Map<String, Integer> RANGO =
            Map.of("EN_PREPARACION", 0, "DESPACHADO", 1, "ENTREGADO", 2, "ANULADO", 2);

    private final HttpServer servidor;
    private final ExecutorService ejecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String origenSeguridad;

    // Estado efímero, protegido por el monitor de esta instancia.
    private final Map<String, Map<String, Object>> eventos = new LinkedHashMap<>();
    private final List<Map<String, Object>> recibidos = new ArrayList<>();
    private final Map<String, Pedido> pedidos = new LinkedHashMap<>();
    private int fallosPendientes;
    private String emisor;
    private Map<String, PublicKey> claves = Map.of();
    private Instant clavesCargadasEn = Instant.EPOCH;

    private static final class Pedido {
        final String idPedido;
        String estadoComercial = "EN_PREPARACION";
        String fechaEstimadaEntrega;
        Instant fechaEstimadaDesde = Instant.EPOCH;
        final List<Map<String, Object>> hitos = new ArrayList<>();

        Pedido(String idPedido) {
            this.idPedido = idPedido;
        }
    }

    public VentasLocal(String direccion, int puerto, String origenSeguridad, boolean habilitado)
            throws IOException {
        if (!habilitado) {
            ejecutor.shutdownNow();
            throw new IllegalStateException(
                    "Herramienta temporal: requiere HABILITAR_VENTAS_LOCAL=true de forma explícita");
        }
        this.origenSeguridad = origenSeguridad.replaceAll("/+$", "");
        servidor = HttpServer.create(new InetSocketAddress(direccion, puerto), 0);
        servidor.setExecutor(ejecutor);
        servidor.createContext("/", this::atender);
    }

    public static void main(String[] argumentos) throws Exception {
        boolean habilitado = "true".equals(System.getenv("HABILITAR_VENTAS_LOCAL"));
        int puerto = Integer.parseInt(System.getenv().getOrDefault("PORT", "8084"));
        String direccion = System.getenv().getOrDefault("DIRECCION_ESCUCHA", "127.0.0.1");
        String seguridad = System.getenv().getOrDefault("SEGURIDAD_URL", "http://127.0.0.1:8090");
        var servidor = new VentasLocal(direccion, puerto, seguridad, habilitado);
        Runtime.getRuntime().addShutdownHook(new Thread(servidor::close));
        servidor.iniciar();
        System.out.println("G2: VENTAS LOCAL TEMPORAL. No es Ventas real ni es apto para producción.");
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
        http.close();
    }

    // ==================== Rutas ====================

    private void atender(HttpExchange intercambio) throws IOException {
        try {
            String ruta = intercambio.getRequestURI().getPath();
            String metodo = intercambio.getRequestMethod();
            if ("/actuator/health".equals(ruta) && "GET".equals(metodo)) {
                responder(intercambio, 200, Map.of("status", "UP", "entorno", "simulado-local"));
            } else if (RUTA_EVENTOS.equals(ruta)) {
                exigirMetodo(metodo, "POST");
                recibirEvento(intercambio);
            } else if ("/control/eventos".equals(ruta)) {
                exigirMetodo(metodo, "GET");
                responder(intercambio, 200, listarEventos());
            } else if (ruta.startsWith("/control/pedidos/")) {
                exigirMetodo(metodo, "GET");
                String idPedido = ruta.substring("/control/pedidos/".length());
                Map<String, Object> pedido = consultarPedido(idPedido);
                if (pedido == null)
                    throw new Rechazo(404, "PEDIDO_NO_ENCONTRADO", "Ningún evento mencionó ese pedido");
                responder(intercambio, 200, pedido);
            } else if ("/control/fallos".equals(ruta)) {
                exigirMetodo(metodo, "POST");
                Object cantidad = objeto(leerJson(intercambio)).get("cantidad");
                if (!(cantidad instanceof BigDecimal numero)
                        || numero.scale() > 0
                        || numero.intValue() < 0
                        || numero.intValue() > MAXIMO_FALLOS)
                    throw new Rechazo(
                            400, "SOLICITUD_INVALIDA", "cantidad debe ser un entero entre 0 y 20");
                synchronized (this) {
                    fallosPendientes = numero.intValue();
                }
                responder(intercambio, 200, Map.of("fallosPendientes", numero.intValue()));
            } else if ("/control/reinicio".equals(ruta)) {
                exigirMetodo(metodo, "POST");
                synchronized (this) {
                    eventos.clear();
                    recibidos.clear();
                    pedidos.clear();
                    fallosPendientes = 0;
                }
                responder(intercambio, 200, Map.of("reiniciado", true));
            } else {
                throw new Rechazo(404, "RUTA_NO_ENCONTRADA", "Ruta no disponible en Ventas local");
            }
        } catch (Rechazo rechazo) {
            responder(
                    intercambio,
                    rechazo.estado,
                    Map.of("code", rechazo.codigo, "detail", rechazo.getMessage()));
        } catch (RuntimeException error) {
            responder(
                    intercambio,
                    500,
                    Map.of("code", "ERROR_INTERNO", "detail", "Error inesperado en Ventas local"));
        } finally {
            intercambio.close();
        }
    }

    private void recibirEvento(HttpExchange intercambio) throws IOException {
        // La autenticación precede a cualquier lectura del contenido.
        autenticar(intercambio.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> evento = objeto(leerJson(intercambio));
        validarEvento(evento);
        Map<String, Object> resultado;
        synchronized (this) {
            if (fallosPendientes > 0) {
                fallosPendientes--;
                throw new Rechazo(
                        503, "VENTAS_NO_DISPONIBLE", "Caída simulada; Despacho debe reintentar");
            }
            resultado = aplicar(evento);
        }
        System.out.println(
                "Ventas local: "
                        + evento.get("tipo")
                        + " "
                        + evento.get("idEvento")
                        + " -> "
                        + resultado.get("resultado"));
        responder(intercambio, 200, resultado);
    }

    // ==================== Reglas de Ventas ====================

    private Map<String, Object> aplicar(Map<String, Object> evento) {
        String idEvento = (String) evento.get("idEvento");
        Map<String, Object> previo = eventos.get(idEvento);
        if (previo != null) {
            // §7.3: un reintento conserva idEvento y contenido; otro contenido es un error del emisor.
            if (!previo.equals(evento))
                throw new Rechazo(
                        409,
                        "EVENTO_CONTENIDO_DISTINTO",
                        "El idEvento ya se recibió con otro contenido");
            return registrar(evento, "DUPLICADO", pedidos.get((String) evento.get("idPedido")));
        }
        eventos.put(idEvento, evento);
        String idPedido = (String) evento.get("idPedido");
        Pedido pedido = pedidos.computeIfAbsent(idPedido, Pedido::new);
        String tipo = (String) evento.get("tipo");
        Instant ocurrido = Instant.parse((String) evento.get("ocurridoEn"));
        Map<String, Object> datos = objeto(evento.get("datos"));

        String resultado = "HITO_REGISTRADO";
        String destino = EFECTOS.get(tipo);
        if (destino != null) {
            int actual = RANGO.get(pedido.estadoComercial);
            int nuevo = RANGO.get(destino);
            if (destino.equals(pedido.estadoComercial)) resultado = "SIN_CAMBIO";
            else if (nuevo > actual) {
                pedido.estadoComercial = destino;
                resultado = "APLICADO";
            } else resultado = "ATRASADO";
        }
        Object fecha =
                "DESPACHO_REPROGRAMADO".equals(tipo)
                        ? datos.get("nuevaFechaProgramada")
                        : "DESPACHO_ASIGNADO".equals(tipo) ? datos.get("fechaProgramada") : null;
        if (fecha instanceof String texto) {
            // Una fecha más antigua no reemplaza la estimación vigente.
            if (!ocurrido.isBefore(pedido.fechaEstimadaDesde)) {
                pedido.fechaEstimadaEntrega = texto;
                pedido.fechaEstimadaDesde = ocurrido;
                if ("HITO_REGISTRADO".equals(resultado)) resultado = "APLICADO";
            } else resultado = "ATRASADO";
        }
        Map<String, Object> hito = new LinkedHashMap<>();
        hito.put("idEvento", idEvento);
        hito.put("tipo", tipo);
        hito.put("estadoNuevo", evento.get("estadoNuevo"));
        hito.put("ocurridoEn", evento.get("ocurridoEn"));
        hito.put("resultado", resultado);
        pedido.hitos.add(hito);
        return registrar(evento, resultado, pedido);
    }

    private Map<String, Object> registrar(Map<String, Object> evento, String resultado, Pedido pedido) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("idEvento", evento.get("idEvento"));
        respuesta.put("tipo", evento.get("tipo"));
        respuesta.put("idPedido", evento.get("idPedido"));
        respuesta.put("resultado", resultado);
        respuesta.put("estadoComercial", pedido == null ? null : pedido.estadoComercial);
        respuesta.put("recibidoEn", Instant.now().toString());
        recibidos.add(respuesta);
        return respuesta;
    }

    private synchronized Map<String, Object> listarEventos() {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("fallosPendientes", fallosPendientes);
        respuesta.put("eventos", List.copyOf(recibidos));
        return respuesta;
    }

    private synchronized Map<String, Object> consultarPedido(String idPedido) {
        Pedido pedido = pedidos.get(idPedido);
        if (pedido == null) return null;
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("idPedido", pedido.idPedido);
        respuesta.put("estadoComercial", pedido.estadoComercial);
        respuesta.put("fechaEstimadaEntrega", pedido.fechaEstimadaEntrega);
        respuesta.put("hitos", List.copyOf(pedido.hitos));
        return respuesta;
    }

    private static void validarEvento(Map<String, Object> evento) {
        for (String campo :
                List.of("idEvento", "tipo", "idPedido", "idDespacho", "estadoNuevo", "ocurridoEn")) {
            if (!(evento.get(campo) instanceof String texto) || texto.isBlank())
                throw invalido("Falta " + campo);
        }
        try {
            UUID.fromString((String) evento.get("idEvento"));
            Instant.parse((String) evento.get("ocurridoEn"));
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw invalido("idEvento debe ser UUID y ocurridoEn un instante ISO-8601");
        }
        if (!TIPOS.contains(evento.get("tipo"))) throw invalido("Tipo de evento no acordado");
        if (!(evento.get("datos") instanceof Map)) throw invalido("datos debe ser un objeto");
        Object estadoAnterior = evento.get("estadoAnterior");
        if (estadoAnterior != null && !(estadoAnterior instanceof String))
            throw invalido("estadoAnterior debe ser texto");
        Map<String, Object> datos = objeto(evento.get("datos"));
        for (String campo : List.of("fechaProgramada", "nuevaFechaProgramada")) {
            Object fecha = datos.get(campo);
            if (fecha == null) continue;
            try {
                LocalDate.parse((String) fecha);
            } catch (ClassCastException | DateTimeParseException error) {
                throw invalido(campo + " debe ser una fecha YYYY-MM-DD");
            }
        }
    }

    private static Rechazo invalido(String detalle) {
        return new Rechazo(400, "EVENTO_INVALIDO", detalle);
    }

    // ==================== Token de servicio ====================

    private void autenticar(String cabecera) {
        if (cabecera == null || !cabecera.startsWith("Bearer "))
            throw new Rechazo(401, "TOKEN_AUSENTE", "Se requiere token de servicio");
        String[] partes = cabecera.substring(7).trim().split("\\.");
        if (partes.length != 3) throw noAutenticado();
        Map<String, Object> encabezado;
        Map<String, Object> claims;
        try {
            encabezado = objeto(Json.leer(base64(partes[0])));
            claims = objeto(Json.leer(base64(partes[1])));
        } catch (RuntimeException error) {
            throw noAutenticado();
        }
        if (!"RS256".equals(encabezado.get("alg"))) throw noAutenticado();
        PublicKey clave = clave(String.valueOf(encabezado.get("kid")));
        try {
            Signature firma = Signature.getInstance("SHA256withRSA");
            firma.initVerify(clave);
            firma.update((partes[0] + "." + partes[1]).getBytes(StandardCharsets.US_ASCII));
            if (!firma.verify(Base64.getUrlDecoder().decode(partes[2]))) throw noAutenticado();
        } catch (java.security.GeneralSecurityException | IllegalArgumentException error) {
            throw noAutenticado();
        }
        if (!(claims.get("exp") instanceof BigDecimal exp)
                || exp.longValue() <= Instant.now().getEpochSecond())
            throw noAutenticado();
        if (!emisor().equals(claims.get("iss"))) throw noAutenticado();
        Object aud = claims.get("aud");
        boolean audienciaValida =
                AUDIENCIA.equals(aud) || aud instanceof List<?> lista && lista.contains(AUDIENCIA);
        String scope = claims.get("scope") instanceof String texto ? texto : "";
        if (!"servicio".equals(claims.get("tipo"))
                || !EMISOR_PERMITIDO.equals(claims.get("sub"))
                || !audienciaValida
                || !List.of(scope.split("\\s+")).contains(SCOPE))
            throw new Rechazo(403, "PERMISO_INSUFICIENTE", "El token no autoriza notificar a Ventas");
    }

    private static Rechazo noAutenticado() {
        return new Rechazo(401, "TOKEN_INVALIDO", "Token de servicio no válido");
    }

    /** Seguridad local genera claves efímeras: un kid desconocido recarga el JWKS. */
    private PublicKey clave(String kid) {
        synchronized (this) {
            PublicKey conocida = claves.get(kid);
            if (conocida != null) return conocida;
            if (Instant.now().isBefore(clavesCargadasEn.plus(ESPERA_RECARGA_CLAVES)))
                throw noAutenticado();
            clavesCargadasEn = Instant.now();
        }
        Map<String, PublicKey> nuevas = new LinkedHashMap<>();
        try {
            Map<String, Object> jwks =
                    objeto(obtener("/api/v1/auth/.well-known/jwks.json"));
            for (Object elemento : (List<?>) jwks.get("keys")) {
                Map<String, Object> jwk = objeto(elemento);
                if (!"RSA".equals(jwk.get("kty"))) continue;
                var especificacion =
                        new RSAPublicKeySpec(
                                new BigInteger(1, base64Bytes((String) jwk.get("n"))),
                                new BigInteger(1, base64Bytes((String) jwk.get("e"))));
                nuevas.put(
                        (String) jwk.get("kid"),
                        KeyFactory.getInstance("RSA").generatePublic(especificacion));
            }
        } catch (Exception error) {
            throw new Rechazo(503, "SEGURIDAD_NO_DISPONIBLE", "No se pudieron obtener las claves");
        }
        synchronized (this) {
            claves = Map.copyOf(nuevas);
            PublicKey encontrada = claves.get(kid);
            if (encontrada == null) throw noAutenticado();
            return encontrada;
        }
    }

    private String emisor() {
        synchronized (this) {
            if (emisor != null) return emisor;
        }
        String leido;
        try {
            leido = (String) objeto(obtener("/api/v1/auth/.well-known/openid-configuration")).get("issuer");
        } catch (Exception error) {
            throw new Rechazo(503, "SEGURIDAD_NO_DISPONIBLE", "No se pudo obtener el emisor");
        }
        if (leido == null) throw new Rechazo(503, "SEGURIDAD_NO_DISPONIBLE", "Emisor no publicado");
        synchronized (this) {
            emisor = leido;
            return emisor;
        }
    }

    private Object obtener(String ruta) throws IOException, InterruptedException {
        var respuesta =
                http.send(
                        HttpRequest.newBuilder(URI.create(origenSeguridad + ruta))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        if (respuesta.statusCode() != 200) throw new IOException("Seguridad respondió " + respuesta.statusCode());
        return Json.leer(respuesta.body());
    }

    // ==================== HTTP y utilidades ====================

    private static void exigirMetodo(String actual, String esperado) {
        if (!esperado.equals(actual))
            throw new Rechazo(405, "METODO_NO_PERMITIDO", "Utilizar " + esperado);
    }

    private static Object leerJson(HttpExchange intercambio) throws IOException {
        try (InputStream entrada = intercambio.getRequestBody()) {
            byte[] bytes = entrada.readNBytes(MAXIMO_CUERPO + 1);
            if (bytes.length > MAXIMO_CUERPO)
                throw new Rechazo(413, "CUERPO_DEMASIADO_GRANDE", "El cuerpo supera 64 KiB");
            try {
                return Json.leer(new String(bytes, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException error) {
                throw new Rechazo(400, "JSON_INVALIDO", "El cuerpo no es JSON válido");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objeto(Object valor) {
        if (valor instanceof Map<?, ?> mapa) return (Map<String, Object>) mapa;
        throw new Rechazo(400, "EVENTO_INVALIDO", "Se esperaba un objeto JSON");
    }

    private static String base64(String texto) {
        return new String(Base64.getUrlDecoder().decode(texto), StandardCharsets.UTF_8);
    }

    private static byte[] base64Bytes(String texto) {
        return Base64.getUrlDecoder().decode(texto);
    }

    private static void responder(HttpExchange intercambio, int estado, Object cuerpo)
            throws IOException {
        byte[] bytes = Json.escribir(cuerpo).getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        intercambio.getResponseHeaders().set("Cache-Control", "no-store");
        intercambio.sendResponseHeaders(estado, bytes.length);
        intercambio.getResponseBody().write(bytes);
    }

    static final class Rechazo extends RuntimeException {
        final int estado;
        final String codigo;

        Rechazo(int estado, String codigo, String detalle) {
            super(detalle);
            this.estado = estado;
            this.codigo = codigo;
        }
    }

    /** JSON mínimo: objetos, listas, texto, números (BigDecimal), booleanos y null. */
    static final class Json {
        private final String texto;
        private int posicion;

        private Json(String texto) {
            this.texto = texto;
        }

        static Object leer(String texto) {
            Json lector = new Json(texto);
            Object valor = lector.valor(0);
            lector.espacios();
            if (lector.posicion != texto.length()) throw new IllegalArgumentException("Sobrante");
            return valor;
        }

        private Object valor(int profundidad) {
            if (profundidad > 32) throw new IllegalArgumentException("Demasiado anidado");
            espacios();
            if (posicion >= texto.length()) throw new IllegalArgumentException("Fin inesperado");
            char c = texto.charAt(posicion);
            if (c == '{') return objetoJson(profundidad);
            if (c == '[') return listaJson(profundidad);
            if (c == '"') return cadena();
            if (texto.startsWith("true", posicion)) return literal("true", Boolean.TRUE);
            if (texto.startsWith("false", posicion)) return literal("false", Boolean.FALSE);
            if (texto.startsWith("null", posicion)) return literal("null", null);
            return numero();
        }

        private Object literal(String palabra, Object valor) {
            posicion += palabra.length();
            return valor;
        }

        private Map<String, Object> objetoJson(int profundidad) {
            Map<String, Object> mapa = new LinkedHashMap<>();
            posicion++;
            espacios();
            if (siguiente('}')) return mapa;
            do {
                espacios();
                if (posicion >= texto.length() || texto.charAt(posicion) != '"')
                    throw new IllegalArgumentException("Clave esperada");
                String clave = cadena();
                espacios();
                if (!siguiente(':')) throw new IllegalArgumentException("':' esperado");
                if (mapa.containsKey(clave)) throw new IllegalArgumentException("Clave repetida");
                mapa.put(clave, valor(profundidad + 1));
                espacios();
            } while (siguiente(','));
            if (!siguiente('}')) throw new IllegalArgumentException("'}' esperado");
            return mapa;
        }

        private List<Object> listaJson(int profundidad) {
            List<Object> lista = new ArrayList<>();
            posicion++;
            espacios();
            if (siguiente(']')) return lista;
            do {
                lista.add(valor(profundidad + 1));
                espacios();
            } while (siguiente(','));
            if (!siguiente(']')) throw new IllegalArgumentException("']' esperado");
            return lista;
        }

        private String cadena() {
            StringBuilder resultado = new StringBuilder();
            posicion++;
            while (posicion < texto.length()) {
                char c = texto.charAt(posicion++);
                if (c == '"') return resultado.toString();
                if (c < 0x20) throw new IllegalArgumentException("Control en texto");
                if (c != '\\') {
                    resultado.append(c);
                    continue;
                }
                if (posicion >= texto.length()) break;
                char escape = texto.charAt(posicion++);
                switch (escape) {
                    case '"', '\\', '/' -> resultado.append(escape);
                    case 'b' -> resultado.append('\b');
                    case 'f' -> resultado.append('\f');
                    case 'n' -> resultado.append('\n');
                    case 'r' -> resultado.append('\r');
                    case 't' -> resultado.append('\t');
                    case 'u' -> {
                        if (posicion + 4 > texto.length())
                            throw new IllegalArgumentException("Escape incompleto");
                        resultado.append(
                                (char) Integer.parseInt(texto.substring(posicion, posicion + 4), 16));
                        posicion += 4;
                    }
                    default -> throw new IllegalArgumentException("Escape inválido");
                }
            }
            throw new IllegalArgumentException("Texto sin cerrar");
        }

        private BigDecimal numero() {
            int inicio = posicion;
            while (posicion < texto.length() && "+-0123456789.eE".indexOf(texto.charAt(posicion)) >= 0)
                posicion++;
            if (inicio == posicion) throw new IllegalArgumentException("Valor inválido");
            try {
                return new BigDecimal(texto.substring(inicio, posicion));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Número inválido");
            }
        }

        private boolean siguiente(char esperado) {
            if (posicion < texto.length() && texto.charAt(posicion) == esperado) {
                posicion++;
                return true;
            }
            return false;
        }

        private void espacios() {
            while (posicion < texto.length() && " \t\r\n".indexOf(texto.charAt(posicion)) >= 0)
                posicion++;
        }

        static String escribir(Object valor) {
            StringBuilder salida = new StringBuilder();
            escribir(valor, salida);
            return salida.toString();
        }

        private static void escribir(Object valor, StringBuilder salida) {
            if (valor == null) salida.append("null");
            else if (valor instanceof String texto) escribirTexto(texto, salida);
            else if (valor instanceof BigDecimal numero) salida.append(numero.toPlainString());
            else if (valor instanceof Number || valor instanceof Boolean) salida.append(valor);
            else if (valor instanceof Map<?, ?> mapa) {
                salida.append('{');
                boolean primero = true;
                for (var entrada : mapa.entrySet()) {
                    if (!primero) salida.append(',');
                    primero = false;
                    escribirTexto(String.valueOf(entrada.getKey()), salida);
                    salida.append(':');
                    escribir(entrada.getValue(), salida);
                }
                salida.append('}');
            } else if (valor instanceof List<?> lista) {
                salida.append('[');
                for (int i = 0; i < lista.size(); i++) {
                    if (i > 0) salida.append(',');
                    escribir(lista.get(i), salida);
                }
                salida.append(']');
            } else escribirTexto(String.valueOf(valor), salida);
        }

        private static void escribirTexto(String texto, StringBuilder salida) {
            salida.append('"');
            for (char c : texto.toCharArray()) {
                switch (c) {
                    case '"' -> salida.append("\\\"");
                    case '\\' -> salida.append("\\\\");
                    case '\n' -> salida.append("\\n");
                    case '\r' -> salida.append("\\r");
                    case '\t' -> salida.append("\\t");
                    default -> {
                        if (c < 0x20) salida.append(String.format("\\u%04x", (int) c));
                        else salida.append(c);
                    }
                }
            }
            salida.append('"');
        }
    }
}
