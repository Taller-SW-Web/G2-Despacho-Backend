package com.modulodespacho.pruebas.integracion.soporte;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cliente HTTP real (java.net.http). No sigue redirecciones para no reenviar un Bearer a otro
 * destino ni interpretar una página de login como éxito, igual que verificar_f02_publicado.py.
 */
public final class ClienteApi {
    public static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIEMPO_MAXIMO = Duration.ofSeconds(10);
    private static final int TAMANO_MAXIMO = 2_000_000;

    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(TIEMPO_MAXIMO)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    /** Estado HTTP, cuerpo textual y su lectura JSON (null si no es JSON). */
    public record Respuesta(int estado, String texto, JsonNode json, java.net.http.HttpHeaders cabeceras) {
        public JsonNode exigirJson(int esperado) {
            if (estado != esperado || json == null)
                throw new AssertionError(
                        "Se esperaba HTTP " + esperado + " con JSON y se obtuvo " + estado);
            return json;
        }
    }

    public Respuesta get(String url, String token) {
        return enviar(base(url, token).GET().build());
    }

    public Respuesta postJson(String url, Object cuerpo, String token, String claveIdempotencia) {
        return postJsonConCabeceras(
                url,
                cuerpo,
                token,
                claveIdempotencia == null ? Map.of() : Map.of("Idempotency-Key", claveIdempotencia));
    }

    public Respuesta postJsonConCabeceras(
            String url, Object cuerpo, String token, Map<String, String> cabeceras) {
        var solicitud = base(url, token).header("Content-Type", "application/json");
        cabeceras.forEach(solicitud::header);
        try {
            solicitud.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(cuerpo)));
        } catch (IOException error) {
            throw new IllegalArgumentException("Cuerpo no serializable", error);
        }
        return enviar(solicitud.build());
    }

    public Respuesta postFormulario(String url, Map<String, String> campos) {
        String cuerpo =
                campos.entrySet().stream()
                        .map(
                                campo ->
                                        URLEncoder.encode(campo.getKey(), StandardCharsets.UTF_8)
                                                + "="
                                                + URLEncoder.encode(
                                                        campo.getValue(), StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));
        return enviar(
                base(url, null)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                        .build());
    }

    private static HttpRequest.Builder base(String url, String token) {
        var solicitud =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIEMPO_MAXIMO)
                        .header("Accept", "application/json, application/problem+json");
        if (token != null) solicitud.header("Authorization", "Bearer " + token);
        return solicitud;
    }

    private Respuesta enviar(HttpRequest solicitud) {
        try {
            HttpResponse<byte[]> respuesta =
                    http.send(solicitud, HttpResponse.BodyHandlers.ofByteArray());
            if (respuesta.body().length > TAMANO_MAXIMO)
                throw new IllegalStateException("Respuesta demasiado grande");
            String texto = new String(respuesta.body(), StandardCharsets.UTF_8);
            JsonNode json;
            try {
                json = texto.isBlank() ? null : JSON.readTree(texto);
            } catch (IOException noEsJson) {
                json = null;
            }
            return new Respuesta(respuesta.statusCode(), texto, json, respuesta.headers());
        } catch (IOException error) {
            // El mensaje no incluye cabeceras, así que nunca expone el token.
            throw new IllegalStateException(
                    "Fallo de red en " + solicitud.method() + " " + solicitud.uri().getPath(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Solicitud interrumpida", error);
        }
    }
}
