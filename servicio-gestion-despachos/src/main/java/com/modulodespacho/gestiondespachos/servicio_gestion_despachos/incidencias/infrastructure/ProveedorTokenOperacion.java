package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Obtiene y renueva el JWT técnico conforme a Seguridad §8.2; nunca usa el JWT humano. */
public final class ProveedorTokenOperacion implements Supplier<String> {

    public static final String SCOPE = "operacion:incidencias:leer";
    private final RestClient seguridad;
    private final String idCliente;
    private final Supplier<String> secretoCliente;
    private final Supplier<String> tokenExterno;
    private final Clock reloj;
    private String token = "";
    private Instant renovarEn = Instant.MIN;

    public ProveedorTokenOperacion(RestClient seguridad, String idCliente, Supplier<String> secretoCliente,
            Supplier<String> tokenExterno, Clock reloj) {
        this.seguridad = seguridad;
        this.idCliente = idCliente;
        this.secretoCliente = secretoCliente;
        this.tokenExterno = tokenExterno;
        this.reloj = reloj;
    }

    @Override
    public synchronized String get() {
        String externo = tokenExterno.get();
        if (externo != null && !externo.isBlank()) return externo.trim();
        Instant ahora = reloj.instant();
        if (ahora.isBefore(renovarEn)) return token;
        token = "";
        renovarEn = Instant.MIN;
        String secreto = secretoCliente.get();
        if (idCliente == null || idCliente.isBlank() || secreto == null || secreto.isBlank()) return "";
        LinkedMultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        formulario.add("grant_type", "client_credentials");
        formulario.add("client_id", idCliente);
        formulario.add("client_secret", secreto);
        formulario.add("scope", SCOPE);
        try {
            Respuesta respuesta = seguridad.post().uri("/api/v1/auth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(formulario)
                    .retrieve().body(Respuesta.class);
            if (respuesta != null && "Bearer".equalsIgnoreCase(respuesta.tokenType())
                    && respuesta.accessToken() != null && respuesta.accessToken().matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                    && respuesta.expiresIn() != null && respuesta.expiresIn() > 0
                    && respuesta.scopes() != null && respuesta.scopes().contains(SCOPE)) {
                renovarEn = ahora.plusSeconds(Math.max(1, respuesta.expiresIn() - Math.min(30, respuesta.expiresIn() / 2)));
                token = respuesta.accessToken();
            }
        } catch (RestClientException | DateTimeException error) {
            // Una emisión fallida no reutiliza tokens vencidos ni filtra secretos al cliente.
        }
        return token;
    }

    public record Respuesta(String accessToken, String tokenType, Long expiresIn, List<String> scopes) {}
}
