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
        try (var emisor =
                        new EmisorSeguridadLocal(
                                "127.0.0.1", 0, "http://seguridad-local:8090", true);
                var cliente =
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            emisor.iniciar();
            String origen = "http://127.0.0.1:" + emisor.puerto();
            var salud = obtener(cliente, origen + "/actuator/health");
            comprobar(salud.statusCode() == 200 && salud.body().contains("\"UP\""), "Salud local");

            var descubrimiento =
                    obtener(cliente, origen + "/api/v1/auth/.well-known/openid-configuration");
            comprobar(
                    descubrimiento.statusCode() == 200
                            && descubrimiento
                                    .body()
                                    .contains("http://seguridad-local:8090/api/v1/auth"),
                    "El emisor publicado debe coincidir con el JWT");

            var jwks = obtener(cliente, origen + "/api/v1/auth/.well-known/jwks.json");
            comprobar(
                    jwks.statusCode() == 200
                            && !jwks.body().contains("\"d\":")
                            && !jwks.body().contains("\"p\":"),
                    "JWKS no debe publicar la clave privada");
            RSAPublicKey publica =
                    (RSAPublicKey)
                            KeyFactory.getInstance("RSA")
                                    .generatePublic(
                                            new RSAPublicKeySpec(
                                                    entero(jwks.body(), "n"),
                                                    entero(jwks.body(), "e")));

            String rutaToken = origen + "/api/v1/auth/token";
            var servicio = enviar(cliente, rutaToken, formularioServicio("cotizaciones:calcular"));
            comprobar(servicio.statusCode() == 200, "Token del consumidor de cotización");
            comprobar(
                    servicio.headers()
                            .firstValue("X-Entorno-Seguridad")
                            .orElse("")
                            .equals("simulado-local"),
                    "Identificar el entorno temporal");
            comprobar(
                    servicio.headers().firstValue("Cache-Control").orElse("").equals("no-store"),
                    "No almacenar respuestas con tokens");
            String token = extraer(servicio.body(), "accessToken");
            String[] partes = token.split("\\.");
            comprobar(partes.length == 3, "El JWT debe tener tres segmentos");
            String cabecera = decodificar(partes[0]);
            String claims = decodificar(partes[1]);
            comprobar(cabecera.contains("\"alg\":\"RS256\""), "Algoritmo RS256");
            comprobar(
                    extraer(cabecera, "kid").equals(extraer(jwks.body(), "kid")),
                    "Selección por kid");
            comprobar(
                    firmaValida(publica, partes[0] + "." + partes[1], partes[2]),
                    "Firma verificable");
            comprobar(
                    !firmaValida(
                            publica, partes[0] + "." + codificar("{\"sub\":\"otro\"}"), partes[2]),
                    "Una alteración del contenido invalida la firma");
            comprobar(
                    claims.contains("\"aud\":[\"api-despacho\"]")
                            && claims.contains("\"tipo\":\"servicio\"")
                            && claims.contains("\"scope\":\"cotizaciones:calcular\""),
                    "Claims de servicio");
            comprobar(
                    numero(claims, "exp") - numero(claims, "iat") == 3600, "Vigencia de servicio");
            UUID.fromString(extraer(claims, "jti"));

            var sinScopeCotizacion =
                    enviar(cliente, rutaToken, formularioServicio("seguimientos:leer"));
            comprobar(
                    sinScopeCotizacion.statusCode() == 200,
                    "Emitir un token válido para probar 403 en cotización");
            String claimsSinScope =
                    decodificar(extraer(sinScopeCotizacion.body(), "accessToken").split("\\.")[1]);
            comprobar(
                    !claimsSinScope.contains("cotizaciones:calcular"),
                    "Conceder solo el scope solicitado");

            var scopeAjeno = enviar(cliente, rutaToken, formularioServicio("despachos:crear"));
            comprobar(scopeAjeno.statusCode() == 403, "No escalar permisos de Marketplace");
            String sinScopes =
                    formulario(
                            Map.of(
                                    "grant_type",
                                    "client_credentials",
                                    "client_id",
                                    "modulo-despacho",
                                    "client_secret",
                                    "despacho-local-no-produccion"));
            var tecnico = enviar(cliente, rutaToken, sinScopes);
            comprobar(
                    tecnico.statusCode() == 200,
                    "modulo-despacho obtiene token de servicio sin pedir scopes");
            String claimsTecnico =
                    decodificar(extraer(tecnico.body(), "accessToken").split("\\.")[1]);
            comprobar(
                    claimsTecnico.contains("\"sub\":\"modulo-despacho\"")
                            && claimsTecnico.contains("\"tipo\":\"servicio\"")
                            && !claimsTecnico.contains("operacion:")
                            && !claimsTecnico.contains("gestion:"),
                    "El token tecnico no lleva scopes internos");
            String scopeInterno =
                    formulario(
                            Map.of(
                                    "grant_type",
                                    "client_credentials",
                                    "client_id",
                                    "modulo-despacho",
                                    "client_secret",
                                    "despacho-local-no-produccion",
                                    "scope",
                                    "operacion:capacidad:reservar"));
            comprobar(
                    enviar(cliente, rutaToken, scopeInterno).statusCode() == 403,
                    "Los scopes internos ya no se conceden");
            String credencialesIncorrectas =
                    formulario(
                            Map.of(
                                    "grant_type",
                                    "client_credentials",
                                    "client_id",
                                    "modulo-marketplace",
                                    "client_secret",
                                    "incorrecto"));
            comprobar(
                    enviar(cliente, rutaToken, credencialesIncorrectas).statusCode() == 401,
                    "Rechazar credenciales incorrectas");

            for (String perfil :
                    new String[] {"gestor", "repartidor", "repartidor-kevin", "sin-rol"}) {
                var usuario =
                        enviar(
                                cliente,
                                origen + "/pruebas/token-usuario",
                                formulario(Map.of("perfil", perfil)));
                comprobar(usuario.statusCode() == 200, "Perfil de prueba " + perfil);
                String claimsUsuario =
                        decodificar(extraer(usuario.body(), "accessToken").split("\\.")[1]);
                comprobar(
                        claimsUsuario.contains("\"tipo\":\"acceso\"")
                                && !claimsUsuario.contains("\"aud\":"),
                        "Token humano sin audiencia de servicio");
                UUID.fromString(extraer(claimsUsuario, "sub"));
                UUID.fromString(extraer(claimsUsuario, "jti"));
                comprobar(
                        numero(claimsUsuario, "exp") - numero(claimsUsuario, "iat") == 900,
                        "Vigencia de usuario");
                comprobar(
                        claimsUsuario.contains(
                                perfil.equals("gestor")
                                        ? "\"roles\":[\"GESTOR_DESPACHO\"]"
                                        : "\"roles\":[]"),
                        "Roles del perfil " + perfil);
            }

            String rutaLogin = origen + "/api/v1/auth/login";
            var acceso =
                    enviar(
                            cliente,
                            rutaLogin,
                            formulario(
                                    Map.of("usuario", "MRojas@despacho.pe", "clave", "Ruta2026!")));
            comprobar(acceso.statusCode() == 200, "Login de prueba del repartidor");
            String claimsAcceso =
                    decodificar(extraer(acceso.body(), "accessToken").split("\\.")[1]);
            comprobar(
                    claimsAcceso.contains("\"tipo\":\"acceso\"")
                            && claimsAcceso.contains("\"roles\":[]")
                            && extraer(claimsAcceso, "sub")
                                    .equals("22222222-2222-4222-8222-222222222222"),
                    "El login emite el mismo JWT humano que el perfil repartidor");
            comprobar(
                    enviar(
                                            cliente,
                                            rutaLogin,
                                            formulario(
                                                    Map.of(
                                                            "usuario",
                                                            "mrojas@despacho.pe",
                                                            "clave",
                                                            "otra")))
                                    .statusCode()
                            == 401,
                    "Rechazar una contraseña incorrecta");
            comprobar(
                    enviar(
                                            cliente,
                                            rutaLogin,
                                            formulario(
                                                    Map.of(
                                                            "usuario",
                                                            "desconocido",
                                                            "clave",
                                                            "Ruta2026!")))
                                    .statusCode()
                            == 401,
                    "Rechazar un usuario inexistente sin revelar cuál dato falló");
            comprobar(obtener(cliente, rutaLogin).statusCode() == 405, "El login solo acepta POST");
            for (var cuenta :
                    Map.of(
                                    "gerardo@despacho.pe",
                                    "22222222-2222-4222-8222-222222222222",
                                    "kevin@despacho.pe",
                                    "44444444-4444-4444-8444-444444444444")
                            .entrySet()) {
                var sesion =
                        enviar(
                                cliente,
                                rutaLogin,
                                formulario(
                                        Map.of("usuario", cuenta.getKey(), "clave", "Ruta2026!")));
                comprobar(sesion.statusCode() == 200, "Login de " + cuenta.getKey());
                String[] jwt = extraer(sesion.body(), "accessToken").split("\\.");
                String identidad = decodificar(jwt[1]);
                comprobar(
                        extraer(identidad, "sub").equals(cuenta.getValue())
                                && identidad.contains("\"tipo\":\"acceso\"")
                                && identidad.contains("\"roles\":[]"),
                        "Cada cuenta debe emitir su identidad de repartidor sin roles globales");
                comprobar(
                        firmaValida(publica, jwt[0] + "." + jwt[1], jwt[2]),
                        "Firma de " + cuenta.getKey());
                comprobar(
                        enviar(
                                                cliente,
                                                rutaLogin,
                                                formulario(
                                                        Map.of(
                                                                "usuario",
                                                                cuenta.getKey(),
                                                                "clave",
                                                                "otra")))
                                        .statusCode()
                                == 401,
                        "Rechazar contraseña incorrecta de " + cuenta.getKey());
            }
            var perfilKevin =
                    enviar(
                            cliente,
                            origen + "/pruebas/token-usuario",
                            formulario(Map.of("perfil", "repartidor-kevin")));
            comprobar(
                    extraer(
                                    decodificar(
                                            extraer(perfilKevin.body(), "accessToken")
                                                    .split("\\.")[1]),
                                    "sub")
                            .equals("44444444-4444-4444-8444-444444444444"),
                    "El perfil directo de Kevin conserva su identidad");

            comprobar(
                    obtener(cliente, rutaToken).statusCode() == 405,
                    "Respetar el método de emisión");
            comprobar(
                    enviar(cliente, rutaToken, "campo=" + "a".repeat(4096)).statusCode() == 413,
                    "Limitar el tamaño del formulario");
            comprobar(
                    enviar(cliente, rutaToken, "client_id=uno&client_id=otro").statusCode() == 400,
                    "Rechazar parámetros ambiguos");
            comprobarSesionHumana(cliente, origen);
            System.out.println("Pruebas del emisor local completadas");
        }
    }

    /** Doble local del contrato G7, incluyendo uso único y revocación por familia. */
    private static void comprobarSesionHumana(HttpClient cliente, String origen) throws Exception {
        String base = origen + "/api/v1/auth";
        var login =
                enviarJson(
                        cliente,
                        base + "/login",
                        Map.of("correo", "gestor@despacho.pe", "contrasena", "Ruta2026!"));
        comprobar(
                login.statusCode() == 200
                        && login.body().contains("\"mfaRequerido\":true")
                        && !login.body().contains("accessToken"),
                "El gestor no recibe sesión antes del OTP");
        String desafio = extraer(login.body(), "challengeToken");
        comprobar(
                enviarJson(
                                        cliente,
                                        base + "/otp/verificar",
                                        Map.of("challengeToken", desafio, "codigo", "123456"))
                                .statusCode()
                        == 410,
                "No se puede verificar sin haber solicitado el código");
        var envio = enviarJson(cliente, base + "/otp/solicitar", Map.of("challengeToken", desafio));
        comprobar(
                envio.statusCode() == 202 && !envio.body().contains("\"codigo\""),
                "La respuesta OTP conserva la forma de G7");
        String codigo =
                extraer(
                        enviarJson(
                                        cliente,
                                        origen + "/pruebas/codigo-otp",
                                        Map.of("challengeToken", desafio))
                                .body(),
                        "codigo");
        String incorrecto = codigo.equals("000000") ? "000001" : "000000";
        for (int intento = 1; intento <= 3; intento++) {
            var fallo =
                    enviarJson(
                            cliente,
                            base + "/otp/verificar",
                            Map.of("challengeToken", desafio, "codigo", incorrecto));
            comprobar(
                    fallo.statusCode() == 401
                            && numero(fallo.body(), "intentosRestantes") == 3 - intento,
                    "El OTP conserva el contador de intentos");
        }
        comprobar(
                enviarJson(
                                cliente,
                                base + "/otp/verificar",
                                Map.of("challengeToken", desafio, "codigo", codigo))
                        .body()
                        .contains("OTP_INTENTOS_AGOTADOS"),
                "El código correcto tampoco entra tras agotar intentos");
        comprobar(
                enviarJson(cliente, base + "/otp/solicitar", Map.of("challengeToken", desafio))
                                .statusCode()
                        == 202,
                "Se puede solicitar un nuevo código sin extender el desafío");
        String nuevoCodigo =
                extraer(
                        enviarJson(
                                        cliente,
                                        origen + "/pruebas/codigo-otp",
                                        Map.of("challengeToken", desafio))
                                .body(),
                        "codigo");
        comprobar(!nuevoCodigo.equals(codigo), "Reenviar reemplaza el código del desafío");
        var verificada =
                enviarJson(
                        cliente,
                        base + "/otp/verificar",
                        Map.of("challengeToken", desafio, "codigo", nuevoCodigo));
        comprobar(
                verificada.statusCode() == 200 && verificada.body().contains("GESTOR_DESPACHO"),
                "OTP correcto entrega el gestor");
        comprobar(
                enviarJson(
                                cliente,
                                base + "/otp/verificar",
                                Map.of("challengeToken", desafio, "codigo", nuevoCodigo))
                        .body()
                        .contains("TOKEN_INVALIDO"),
                "El desafío ya usado no se puede canjear otra vez");
        var otroLogin =
                enviarJson(
                        cliente,
                        base + "/login",
                        Map.of("correo", "gestor@despacho.pe", "contrasena", "Ruta2026!"));
        String otroDesafio = extraer(otroLogin.body(), "challengeToken");
        comprobar(
                enviarJson(cliente, base + "/otp/solicitar", Map.of("challengeToken", otroDesafio))
                                .statusCode()
                        == 202,
                "Tercera solicitud por usuario");
        var limitado =
                enviarJson(cliente, base + "/otp/solicitar", Map.of("challengeToken", otroDesafio));
        comprobar(
                limitado.statusCode() == 429
                        && limitado.headers().firstValue("Retry-After").isPresent(),
                "Cuarta solicitud limitada por usuario");

        String refresco = extraer(verificada.body(), "refreshToken");
        var renovada = enviarJson(cliente, base + "/refresh", Map.of("refreshToken", refresco));
        comprobar(
                renovada.statusCode() == 200
                        && !extraer(renovada.body(), "refreshToken").equals(refresco),
                "El refresco rota");
        var independiente =
                enviarJson(
                        cliente,
                        base + "/login",
                        Map.of("correo", "gerardo@despacho.pe", "contrasena", "Ruta2026!"));
        comprobar(
                independiente.statusCode() == 200 && !independiente.body().contains("mfaRequerido"),
                "Repartidor entra sin rol global ni MFA obligatorio");
        comprobar(
                enviarJson(cliente, base + "/refresh", Map.of("refreshToken", refresco))
                                .statusCode()
                        == 401,
                "Reusar un refresco revoca la familia");
        comprobar(
                enviarJson(
                                        cliente,
                                        base + "/refresh",
                                        Map.of(
                                                "refreshToken",
                                                extraer(renovada.body(), "refreshToken")))
                                .statusCode()
                        == 401,
                "El descendiente de la familia también queda revocado");
        var otraSesion =
                enviarJson(
                        cliente,
                        base + "/refresh",
                        Map.of("refreshToken", extraer(independiente.body(), "refreshToken")));
        comprobar(otraSesion.statusCode() == 200, "Las sesiones independientes se conservan");
        String acceso = extraer(otraSesion.body(), "accessToken"),
                ultimo = extraer(otraSesion.body(), "refreshToken");
        var perfil =
                cliente.send(
                        HttpRequest.newBuilder(URI.create(base + "/me"))
                                .header("Authorization", "Bearer " + acceso)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        comprobar(
                perfil.statusCode() == 200 && perfil.body().contains("gerardo@despacho.pe"),
                "Perfil de la sesión humana");
        comprobar(
                enviarJson(cliente, base + "/logout", Map.of("refreshToken", ultimo)).statusCode()
                        == 204,
                "Logout sin JWT de acceso");
        comprobar(
                enviarJson(cliente, base + "/logout", Map.of("refreshToken", ultimo)).statusCode()
                        == 204,
                "Logout idempotente");
        comprobar(
                enviarJson(cliente, base + "/refresh", Map.of("refreshToken", ultimo)).statusCode()
                        == 401,
                "Logout impide renovar");
        comprobar(
                enviarJson(
                                        cliente,
                                        base + "/login",
                                        Map.of(
                                                "correo",
                                                "gerardo@despacho.pe",
                                                "contrasena",
                                                "otra"))
                                .statusCode()
                        == 401,
                "Credenciales JSON incorrectas");
    }

    private static HttpResponse<String> enviarJson(
            HttpClient cliente, String ruta, Map<String, String> campos) throws Exception {
        String cuerpo =
                campos.entrySet().stream()
                        .map(
                                entrada ->
                                        "\""
                                                + entrada.getKey()
                                                + "\":\""
                                                + entrada.getValue()
                                                + "\"")
                        .collect(Collectors.joining(",", "{", "}"));
        return cliente.send(
                HttpRequest.newBuilder(URI.create(ruta))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void comprobarActivacionExplicita() throws Exception {
        try (var inesperado = new EmisorSeguridadLocal("127.0.0.1", 0, "http://local", false)) {
            throw new AssertionError("El emisor no debe iniciarse sin habilitación explícita");
        } catch (IllegalStateException esperado) {
            comprobar(
                    esperado.getMessage().contains("HABILITAR_SEGURIDAD_LOCAL"),
                    "Explicar la habilitación");
        }
    }

    private static String formularioServicio(String scope) {
        return formulario(
                Map.of(
                        "grant_type",
                        "client_credentials",
                        "client_id",
                        "modulo-marketplace",
                        "client_secret",
                        "despacho-local-no-produccion",
                        "scope",
                        scope));
    }

    private static String formulario(Map<String, String> campos) {
        return campos.entrySet().stream()
                .map(
                        entrada ->
                                URLEncoder.encode(entrada.getKey(), StandardCharsets.UTF_8)
                                        + "="
                                        + URLEncoder.encode(
                                                entrada.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static HttpResponse<String> obtener(HttpClient cliente, String ruta) throws Exception {
        return cliente.send(
                HttpRequest.newBuilder(URI.create(ruta))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> enviar(HttpClient cliente, String ruta, String formulario)
            throws Exception {
        return cliente.send(
                HttpRequest.newBuilder(URI.create(ruta))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formulario))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String extraer(String json, String campo) {
        var coincidencia =
                Pattern.compile("\"" + Pattern.quote(campo) + "\":\"([^\"]+)\"").matcher(json);
        comprobar(coincidencia.find(), "Debe existir " + campo);
        return coincidencia.group(1);
    }

    private static long numero(String json, String campo) {
        var coincidencia =
                Pattern.compile("\"" + Pattern.quote(campo) + "\":([0-9]+)").matcher(json);
        comprobar(coincidencia.find(), "Debe existir " + campo);
        return Long.parseLong(coincidencia.group(1));
    }

    private static BigInteger entero(String jwks, String campo) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(extraer(jwks, campo)));
    }

    private static boolean firmaValida(RSAPublicKey publica, String contenido, String firma)
            throws Exception {
        var verificador = Signature.getInstance("SHA256withRSA");
        verificador.initVerify(publica);
        verificador.update(contenido.getBytes(StandardCharsets.US_ASCII));
        return verificador.verify(Base64.getUrlDecoder().decode(firma));
    }

    private static String decodificar(String segmento) {
        return new String(Base64.getUrlDecoder().decode(segmento), StandardCharsets.UTF_8);
    }

    private static String codificar(String contenido) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(contenido.getBytes(StandardCharsets.UTF_8));
    }

    private static void comprobar(boolean condicion, String mensaje) {
        if (!condicion) {
            throw new AssertionError(mensaje);
        }
    }
}
