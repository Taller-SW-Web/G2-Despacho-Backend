import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Pruebas con HTTP y verificación independiente de la firma usando el JWKS publicado. */
public final class EmisorSeguridadLocalPruebas {

    public static void main(String[] argumentos) throws Exception {
        comprobarActivacionExplicita();
        try (var emisor = new EmisorSeguridadLocal(
                "127.0.0.1", 0, "http://seguridad-local:8090", true);
                var cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            emisor.iniciar();
            String origen = "http://127.0.0.1:" + emisor.puerto();
            var salud = obtener(cliente, origen + "/actuator/health");
            comprobar(salud.statusCode() == 200 && salud.body().contains("\"UP\""), "Salud local");

            var descubrimiento = obtener(cliente, origen + "/api/v1/auth/.well-known/openid-configuration");
            comprobar(descubrimiento.statusCode() == 200
                    && descubrimiento.body().contains("http://seguridad-local:8090/api/v1/auth"),
                    "El emisor publicado debe coincidir con el JWT");

            var jwks = obtener(cliente, origen + "/api/v1/auth/.well-known/jwks.json");
            comprobar(jwks.statusCode() == 200 && !jwks.body().contains("\"d\":")
                    && !jwks.body().contains("\"p\":"), "JWKS no debe publicar la clave privada");
            RSAPublicKey publica = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(entero(jwks.body(), "n"), entero(jwks.body(), "e")));

            String rutaToken = origen + "/api/v1/auth/token";
            var servicio = enviar(cliente, rutaToken, formularioServicio("cotizaciones:calcular"));
            comprobar(servicio.statusCode() == 200, "Token del consumidor de cotización");
            comprobar(servicio.headers().firstValue("X-Entorno-Seguridad").orElse("")
                    .equals("simulado-local"), "Identificar el entorno temporal");
            comprobar(servicio.headers().firstValue("Cache-Control").orElse("").equals("no-store"),
                    "No almacenar respuestas con tokens");
            String token = extraer(servicio.body(), "accessToken");
            String[] partes = token.split("\\.");
            comprobar(partes.length == 3, "El JWT debe tener tres segmentos");
            String cabecera = decodificar(partes[0]);
            String claims = decodificar(partes[1]);
            comprobar(cabecera.contains("\"alg\":\"RS256\""), "Algoritmo RS256");
            comprobar(extraer(cabecera, "kid").equals(extraer(jwks.body(), "kid")), "Selección por kid");
            comprobar(firmaValida(publica, partes[0] + "." + partes[1], partes[2]), "Firma verificable");
            comprobar(!firmaValida(publica, partes[0] + "." + codificar("{\"sub\":\"otro\"}"), partes[2]),
                    "Una alteración del contenido invalida la firma");
            comprobar(claims.contains("\"aud\":[\"api-despacho\"]")
                    && claims.contains("\"tipo\":\"servicio\"")
                    && claims.contains("\"scope\":\"cotizaciones:calcular\""), "Claims de servicio");
            comprobar(numero(claims, "exp") - numero(claims, "iat") == 3600, "Vigencia de servicio");
            UUID.fromString(extraer(claims, "jti"));

            var sinScopeCotizacion = enviar(cliente, rutaToken, formularioServicio("seguimientos:leer"));
            comprobar(sinScopeCotizacion.statusCode() == 200, "Emitir un token válido para probar 403 en cotización");
            String claimsSinScope = decodificar(extraer(sinScopeCotizacion.body(), "accessToken").split("\\.")[1]);
            comprobar(!claimsSinScope.contains("cotizaciones:calcular"), "Conceder solo el scope solicitado");

            var scopeAjeno = enviar(cliente, rutaToken, formularioServicio("despachos:crear"));
            comprobar(scopeAjeno.statusCode() == 403, "No escalar permisos de Marketplace");
            String credencialesIncorrectas = formulario(Map.of("grant_type", "client_credentials",
                    "client_id", "modulo-marketplace", "client_secret", "incorrecto"));
            comprobar(enviar(cliente, rutaToken, credencialesIncorrectas).statusCode() == 401,
                    "Rechazar credenciales incorrectas");

            for (String perfil : new String[]{"gestor", "repartidor", "sin-rol"}) {
                var usuario = enviar(cliente, origen + "/pruebas/token-usuario", formulario(Map.of("perfil", perfil)));
                comprobar(usuario.statusCode() == 200, "Perfil de prueba " + perfil);
                String claimsUsuario = decodificar(extraer(usuario.body(), "accessToken").split("\\.")[1]);
                comprobar(claimsUsuario.contains("\"tipo\":\"acceso\"") && !claimsUsuario.contains("\"aud\":"),
                        "Token humano sin audiencia de servicio");
                UUID.fromString(extraer(claimsUsuario, "sub"));
                UUID.fromString(extraer(claimsUsuario, "jti"));
                comprobar(numero(claimsUsuario, "exp") - numero(claimsUsuario, "iat") == 900,
                        "Vigencia de usuario");
                comprobar(claimsUsuario.contains(perfil.equals("gestor")
                        ? "\"roles\":[\"GESTOR_DESPACHO\"]" : "\"roles\":[]"), "Roles del perfil " + perfil);
            }

            comprobar(obtener(cliente, rutaToken).statusCode() == 405, "Respetar el método de emisión");
            comprobar(enviar(cliente, rutaToken, "campo=" + "a".repeat(4096)).statusCode() == 413,
                    "Limitar el tamaño del formulario");
            comprobar(enviar(cliente, rutaToken, "client_id=uno&client_id=otro").statusCode() == 400,
                    "Rechazar parámetros ambiguos");
            System.out.println("Pruebas del emisor local completadas");
        }
    }

    private static void comprobarActivacionExplicita() throws Exception {
        try (var inesperado = new EmisorSeguridadLocal("127.0.0.1", 0, "http://local", false)) {
            throw new AssertionError("El emisor no debe iniciarse sin habilitación explícita");
        } catch (IllegalStateException esperado) {
            comprobar(esperado.getMessage().contains("HABILITAR_SEGURIDAD_LOCAL"), "Explicar la habilitación");
        }
    }

    private static String formularioServicio(String scope) {
        return formulario(Map.of("grant_type", "client_credentials", "client_id", "modulo-marketplace",
                "client_secret", "despacho-local-no-produccion", "scope", scope));
    }

    private static String formulario(Map<String, String> campos) {
        return campos.entrySet().stream().map(entrada ->
                URLEncoder.encode(entrada.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entrada.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static HttpResponse<String> obtener(HttpClient cliente, String ruta) throws Exception {
        return cliente.send(HttpRequest.newBuilder(URI.create(ruta)).timeout(Duration.ofSeconds(5))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> enviar(HttpClient cliente, String ruta, String formulario) throws Exception {
        return cliente.send(HttpRequest.newBuilder(URI.create(ruta)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formulario)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String extraer(String json, String campo) {
        var coincidencia = Pattern.compile("\"" + Pattern.quote(campo) + "\":\"([^\"]+)\"").matcher(json);
        comprobar(coincidencia.find(), "Debe existir " + campo);
        return coincidencia.group(1);
    }

    private static long numero(String json, String campo) {
        var coincidencia = Pattern.compile("\"" + Pattern.quote(campo) + "\":([0-9]+)").matcher(json);
        comprobar(coincidencia.find(), "Debe existir " + campo);
        return Long.parseLong(coincidencia.group(1));
    }

    private static BigInteger entero(String jwks, String campo) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(extraer(jwks, campo)));
    }

    private static boolean firmaValida(RSAPublicKey publica, String contenido, String firma) throws Exception {
        var verificador = Signature.getInstance("SHA256withRSA");
        verificador.initVerify(publica);
        verificador.update(contenido.getBytes(StandardCharsets.US_ASCII));
        return verificador.verify(Base64.getUrlDecoder().decode(firma));
    }

    private static String decodificar(String segmento) {
        return new String(Base64.getUrlDecoder().decode(segmento), StandardCharsets.UTF_8);
    }

    private static String codificar(String contenido) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(contenido.getBytes(StandardCharsets.UTF_8));
    }

    private static void comprobar(boolean condicion, String mensaje) {
        if (!condicion) {
            throw new AssertionError(mensaje);
        }
    }
}
