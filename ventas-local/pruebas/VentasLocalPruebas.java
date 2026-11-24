import com.sun.net.httpserver.HttpServer;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pruebas HTTP de Ventas local con un JWKS propio, sin depender de Seguridad local. */
public final class VentasLocalPruebas {

    private static final String EMISOR = "http://seguridad-local:8090/api/v1/auth";
    private static final String KID = "prueba-ventas";

    public static void main(String[] argumentos) throws Exception {
        comprobarActivacionExplicita();
        KeyPair claves = generarClaves();
        KeyPair ajenas = generarClaves();
        HttpServer seguridad = seguridadFalsa((RSAPublicKey) claves.getPublic());
        seguridad.start();
        String origenSeguridad = "http://127.0.0.1:" + seguridad.getAddress().getPort();
        try (var ventas = new VentasLocal("127.0.0.1", 0, origenSeguridad, true);
                var cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            ventas.iniciar();
            String origen = "http://127.0.0.1:" + ventas.puerto();
            String ruta = origen + VentasLocal.RUTA_EVENTOS;
            String valido = token(claves, KID, claimsValidos());

            comprobar(
                    obtener(cliente, origen + "/actuator/health").statusCode() == 200, "Salud local");

            // ---------- Autenticación (§7.3) ----------
            String cuerpo = evento(UUID.randomUUID(), "DESPACHO_EN_CAMINO", "PED-1", "2026-11-20T10:00:00Z", Map.of());
            comprobar(publicar(cliente, ruta, null, cuerpo).statusCode() == 401, "Sin token: 401");
            comprobar(
                    publicar(cliente, ruta, token(ajenas, KID, claimsValidos()), cuerpo).statusCode() == 401,
                    "Firma de otra clave: 401");
            comprobar(
                    publicar(cliente, ruta, token(claves, KID, con("exp", Instant.now().getEpochSecond() - 5)), cuerpo)
                                    .statusCode()
                            == 401,
                    "Token vencido: 401");
            comprobar(
                    publicar(cliente, ruta, token(claves, KID, con("iss", "http://otro/api/v1/auth")), cuerpo)
                                    .statusCode()
                            == 401,
                    "Emisor ajeno: 401");
            comprobar(
                    publicar(cliente, ruta, token(claves, KID, con("aud", List.of("api-despacho"))), cuerpo)
                                    .statusCode()
                            == 403,
                    "Audiencia de Despacho: 403");
            comprobar(
                    publicar(cliente, ruta, token(claves, KID, con("scope", "seguimientos:leer")), cuerpo)
                                    .statusCode()
                            == 403,
                    "Sin scope de Ventas: 403");
            comprobar(
                    publicar(cliente, ruta, token(claves, KID, con("sub", "modulo-marketplace")), cuerpo)
                                    .statusCode()
                            == 403,
                    "Otro módulo: 403");
            comprobar(
                    !obtener(cliente, origen + "/control/eventos").body().contains("PED-1"),
                    "Un rechazo de autenticación no registra el evento");

            // ---------- Validación ----------
            comprobar(publicar(cliente, ruta, valido, "{").statusCode() == 400, "JSON inválido: 400");
            comprobar(
                    publicar(cliente, ruta, valido, evento(UUID.randomUUID(), "DESPACHO_INVENTADO", "PED-1", "2026-11-20T10:00:00Z", Map.of()))
                                    .statusCode()
                            == 400,
                    "Tipo no acordado: 400");
            comprobar(
                    publicar(cliente, ruta, valido, cuerpo.replace("\"idEvento\":\"", "\"idEvento\":\"EVT-"))
                                    .statusCode()
                            == 400,
                    "idEvento con prefijo: 400");

            // ---------- Efecto y deduplicación ----------
            var enCamino = publicar(cliente, ruta, valido, cuerpo);
            comprobar(
                    enCamino.statusCode() == 200
                            && enCamino.body().contains("\"resultado\":\"APLICADO\"")
                            && enCamino.body().contains("\"estadoComercial\":\"DESPACHADO\""),
                    "EN_CAMINO cambia el pedido a DESPACHADO");
            var repetido = publicar(cliente, ruta, valido, cuerpo);
            comprobar(
                    repetido.statusCode() == 200 && repetido.body().contains("\"resultado\":\"DUPLICADO\""),
                    "Un reintento con el mismo idEvento responde 2xx sin reaplicar");
            String alterado = cuerpo.replace("2026-11-20T10:00:00Z", "2026-11-20T10:05:00Z");
            comprobar(
                    publicar(cliente, ruta, valido, alterado).statusCode() == 409,
                    "Mismo idEvento con otro contenido: 409");

            // ---------- Orden: un evento atrasado no hace retroceder ----------
            var entregado =
                    publicar(cliente, ruta, valido, evento(UUID.randomUUID(), "DESPACHO_ENTREGADO", "PED-2", "2026-11-20T12:00:00Z", Map.of("fechaEntrega", "2026-11-20T12:00:00Z")));
            comprobar(
                    entregado.body().contains("\"estadoComercial\":\"ENTREGADO\""),
                    "ENTREGADO aplicado aunque EN_CAMINO aún no llegó");
            var atrasado =
                    publicar(cliente, ruta, valido, evento(UUID.randomUUID(), "DESPACHO_EN_CAMINO", "PED-2", "2026-11-20T11:00:00Z", Map.of()));
            comprobar(
                    atrasado.statusCode() == 200
                            && atrasado.body().contains("\"resultado\":\"ATRASADO\"")
                            && atrasado.body().contains("\"estadoComercial\":\"ENTREGADO\""),
                    "EN_CAMINO atrasado no reabre un pedido ENTREGADO");
            var pedido = obtener(cliente, origen + "/control/pedidos/PED-2");
            comprobar(
                    pedido.statusCode() == 200
                            && pedido.body().contains("\"estadoComercial\":\"ENTREGADO\"")
                            && pedido.body().contains("DESPACHO_EN_CAMINO"),
                    "El evento atrasado queda registrado para auditoría");

            // ---------- Fecha estimada ----------
            publicar(cliente, ruta, valido, evento(UUID.randomUUID(), "DESPACHO_REPROGRAMADO", "PED-3", "2026-11-21T09:00:00Z", Map.of("nuevaFechaProgramada", "2026-11-24")));
            publicar(cliente, ruta, valido, evento(UUID.randomUUID(), "DESPACHO_REPROGRAMADO", "PED-3", "2026-11-21T08:00:00Z", Map.of("nuevaFechaProgramada", "2026-11-22")));
            comprobar(
                    obtener(cliente, origen + "/control/pedidos/PED-3").body().contains("\"fechaEstimadaEntrega\":\"2026-11-24\""),
                    "Una reprogramación más antigua no reemplaza la fecha vigente");
            comprobar(
                    publicar(cliente, ruta, valido, evento(UUID.randomUUID(), "DESPACHO_REPROGRAMADO", "PED-3", "2026-11-21T10:00:00Z", Map.of("nuevaFechaProgramada", "24/11/2026")))
                                    .statusCode()
                            == 400,
                    "Fecha programada con formato inválido: 400");


            // ---------- Caída simulada ----------
            comprobar(
                    publicar(cliente, origen + "/control/fallos", null, "{\"cantidad\":2}").statusCode() == 200,
                    "Programar caídas");
            String tras = evento(UUID.randomUUID(), "DESPACHO_FALLIDO", "PED-4", "2026-11-22T10:00:00Z", Map.of("motivo", "CLIENTE_AUSENTE", "numeroIntento", 1));
            comprobar(publicar(cliente, ruta, valido, tras).statusCode() == 503, "Primera caída: 503");
            comprobar(publicar(cliente, ruta, valido, tras).statusCode() == 503, "Segunda caída: 503");
            var recuperado = publicar(cliente, ruta, valido, tras);
            comprobar(
                    recuperado.statusCode() == 200
                            && recuperado.body().contains("\"resultado\":\"HITO_REGISTRADO\""),
                    "Tras la caída, el reintento se procesa por primera vez");
            comprobar(
                    publicar(cliente, origen + "/control/fallos", null, "{\"cantidad\":99}").statusCode() == 400,
                    "Límite de caídas simuladas");

            comprobar(
                    publicar(cliente, origen + "/control/reinicio", null, "{}").statusCode() == 200
                            && obtener(cliente, origen + "/control/pedidos/PED-2").statusCode() == 404,
                    "El reinicio limpia el estado");
        } finally {
            seguridad.stop(0);
        }
        System.out.println("Pruebas de Ventas local: OK");
    }

    private static void comprobarActivacionExplicita() throws Exception {
        try {
            new VentasLocal("127.0.0.1", 0, "http://127.0.0.1:1", false).close();
        } catch (IllegalStateException esperado) {
            return;
        }
        throw new AssertionError("Debe exigir HABILITAR_VENTAS_LOCAL=true");
    }

    private static Map<String, Object> claimsValidos() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", EMISOR);
        claims.put("sub", "modulo-despacho");
        claims.put("tipo", "servicio");
        claims.put("aud", List.of("api-ventas"));
        claims.put("scope", "ventas:pedidos:estado");
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", Instant.now().getEpochSecond() + 3600);
        return claims;
    }

    private static Map<String, Object> con(String clave, Object valor) {
        Map<String, Object> claims = claimsValidos();
        claims.put(clave, valor);
        return claims;
    }

    private static String evento(UUID id, String tipo, String pedido, String ocurrido, Map<String, Object> datos) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("idEvento", id.toString());
        cuerpo.put("tipo", tipo);
        cuerpo.put("idPedido", pedido);
        cuerpo.put("idDespacho", UUID.nameUUIDFromBytes(pedido.getBytes(StandardCharsets.UTF_8)).toString());
        cuerpo.put("estadoAnterior", "ASIGNADO");
        cuerpo.put("estadoNuevo", tipo.replaceFirst("^DESPACHO_", ""));
        cuerpo.put("ocurridoEn", ocurrido);
        cuerpo.put("datos", datos);
        return VentasLocal.Json.escribir(cuerpo);
    }

    private static String token(KeyPair claves, String kid, Map<String, Object> claims) throws Exception {
        var codificador = Base64.getUrlEncoder().withoutPadding();
        String cabecera =
                codificador.encodeToString(
                        VentasLocal.Json.escribir(Map.of("alg", "RS256", "typ", "JWT", "kid", kid))
                                .getBytes(StandardCharsets.UTF_8));
        String cuerpo =
                codificador.encodeToString(VentasLocal.Json.escribir(claims).getBytes(StandardCharsets.UTF_8));
        Signature firma = Signature.getInstance("SHA256withRSA");
        firma.initSign(claves.getPrivate());
        firma.update((cabecera + "." + cuerpo).getBytes(StandardCharsets.US_ASCII));
        return cabecera + "." + cuerpo + "." + codificador.encodeToString(firma.sign());
    }

    private static KeyPair generarClaves() throws Exception {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        return generador.generateKeyPair();
    }

    private static HttpServer seguridadFalsa(RSAPublicKey publica) throws Exception {
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String descubrimiento = VentasLocal.Json.escribir(Map.of("issuer", EMISOR));
        String jwks =
                VentasLocal.Json.escribir(
                        Map.of(
                                "keys",
                                List.of(
                                        Map.of(
                                                "kty", "RSA",
                                                "kid", KID,
                                                "alg", "RS256",
                                                "n", entero(publica.getModulus()),
                                                "e", entero(publica.getPublicExponent())))));
        servidor.createContext(
                "/api/v1/auth/.well-known/openid-configuration", i -> escribir(i, descubrimiento));
        servidor.createContext("/api/v1/auth/.well-known/jwks.json", i -> escribir(i, jwks));
        return servidor;
    }

    private static void escribir(com.sun.net.httpserver.HttpExchange intercambio, String cuerpo)
            throws java.io.IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        intercambio.sendResponseHeaders(200, bytes.length);
        intercambio.getResponseBody().write(bytes);
        intercambio.close();
    }

    private static String entero(BigInteger valor) {
        byte[] bytes = valor.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static HttpResponse<String> obtener(HttpClient cliente, String url) throws Exception {
        return cliente.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> publicar(HttpClient cliente, String url, String token, String cuerpo)
            throws Exception {
        var peticion =
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo));
        if (token != null) peticion.header("Authorization", "Bearer " + token);
        return cliente.send(peticion.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void comprobar(boolean condicion, String descripcion) {
        if (!condicion) throw new AssertionError("Falló: " + descripcion);
        System.out.println("OK  " + descripcion);
    }
}
