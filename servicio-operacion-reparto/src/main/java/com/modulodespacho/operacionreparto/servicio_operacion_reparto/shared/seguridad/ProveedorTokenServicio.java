package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Credencial propia hacia Gestión, con renovación; nunca reenvía el JWT del repartidor. */
public final class ProveedorTokenServicio implements Supplier<String> {
    private final RestClient cliente;
    private final String idCliente;
    private final String secreto;
    private final String scopes;
    private final Supplier<String> tokenConfigurado;
    private final Clock reloj;
    private String token = "";
    private Instant renovarEn = Instant.MIN;
    public ProveedorTokenServicio(RestClient cliente, String idCliente, String secreto, String scopes,
            Supplier<String> tokenConfigurado, Clock reloj) {
        this.cliente = cliente; this.idCliente = idCliente; this.secreto = secreto;
        this.scopes = scopes; this.tokenConfigurado = tokenConfigurado; this.reloj = reloj;
    }
    @Override public synchronized String get() {
        String configurado = tokenConfigurado.get();
        if (configurado != null && !configurado.isBlank()) return configurado.trim();
        Instant ahora = reloj.instant();
        if (ahora.isBefore(renovarEn)) return token;
        if (secreto == null || secreto.isBlank()) return "";
        var formulario = new LinkedMultiValueMap<String, String>();
        formulario.add("grant_type", "client_credentials"); formulario.add("client_id", idCliente);
        formulario.add("client_secret", secreto);
        if (scopes != null && !scopes.isBlank()) formulario.add("scope", scopes);
        token = ""; renovarEn = Instant.MIN;
        try {
            Respuesta respuesta = cliente.post().uri("/api/v1/auth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formulario).retrieve().body(Respuesta.class);
            var requeridos = scopes == null || scopes.isBlank() ? List.<String>of() : Arrays.asList(scopes.split("\\s+"));
            if (respuesta != null && "Bearer".equalsIgnoreCase(respuesta.tokenType()) && respuesta.accessToken() != null
                    && respuesta.accessToken().matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                    && respuesta.expiresIn() != null && respuesta.expiresIn() > 0
                    && (requeridos.isEmpty() || respuesta.scopes() != null && respuesta.scopes().containsAll(requeridos))) {
                renovarEn = ahora.plusSeconds(Math.max(1, respuesta.expiresIn() - Math.min(30, respuesta.expiresIn() / 2)));
                token = respuesta.accessToken();
            }
        } catch (RuntimeException error) { token = ""; renovarEn = Instant.MIN; }
        return token;
    }
    public record Respuesta(String accessToken, String tokenType, Long expiresIn, List<String> scopes, String entorno) {}
}
