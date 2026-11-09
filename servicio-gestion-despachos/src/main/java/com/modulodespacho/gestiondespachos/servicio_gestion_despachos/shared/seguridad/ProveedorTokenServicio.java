package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Credencial propia por dependencia, con renovación; nunca reenvía el JWT entrante. */
public final class ProveedorTokenServicio implements Supplier<String> {
    private static final Logger REGISTRO = LoggerFactory.getLogger(ProveedorTokenServicio.class);
    private final RestClient cliente;
    private final String idCliente;
    private final String secreto;
    private final String scopes;
    private final Supplier<String> tokenConfigurado;
    private final Clock reloj;
    private String token = "";
    private Instant renovarEn = Instant.MIN;

    public ProveedorTokenServicio(
            RestClient cliente,
            String idCliente,
            String secreto,
            String scopes,
            Supplier<String> tokenConfigurado,
            Clock reloj) {
        this.cliente = cliente;
        this.idCliente = idCliente;
        this.secreto = secreto;
        this.scopes = scopes;
        this.tokenConfigurado = tokenConfigurado;
        this.reloj = reloj;
    }

    @Override
    public synchronized String get() {
        String configurado = tokenConfigurado.get();
        if (configurado != null && !configurado.isBlank()) return configurado.trim();
        Instant ahora = reloj.instant();
        if (ahora.isBefore(renovarEn)) return token;
        if (secreto == null || secreto.isBlank()) return "";
        var formulario = new LinkedMultiValueMap<String, String>();
        formulario.add("grant_type", "client_credentials");
        formulario.add("client_id", idCliente);
        formulario.add("client_secret", secreto);
        if (scopes != null && !scopes.isBlank()) formulario.add("scope", scopes);
        token = "";
        renovarEn = Instant.MIN;
        long inicio = System.nanoTime();
        try {
            Respuesta respuesta =
                    cliente.post()
                            .uri("/api/v1/auth/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(formulario)
                            .retrieve()
                            .body(Respuesta.class);
            var requeridos =
                    scopes == null || scopes.isBlank()
                            ? List.<String>of()
                            : Arrays.asList(scopes.split("\\s+"));
            if (respuesta != null
                    && "Bearer".equalsIgnoreCase(respuesta.tokenType())
                    && respuesta.accessToken() != null
                    && respuesta
                            .accessToken()
                            .matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                    && respuesta.expiresIn() != null
                    && respuesta.expiresIn() > 0
                    && (requeridos.isEmpty()
                            || respuesta.scopes() != null
                                    && respuesta.scopes().containsAll(requeridos))) {
                renovarEn =
                        ahora.plusSeconds(
                                Math.max(
                                        1,
                                        respuesta.expiresIn()
                                                - Math.min(30, respuesta.expiresIn() / 2)));
                token = respuesta.accessToken();
            } else {
                registrarFallo("respuesta_invalida_o_permiso_ausente", null, inicio);
            }
        } catch (RestClientResponseException error) {
            registrarFallo("respuesta_http", error.getStatusCode().value(), inicio);
        } catch (RuntimeException error) {
            registrarFallo(
                    DiagnosticoJwt.tiempoAgotado(error) ? "tiempo_agotado" : "consulta_fallida",
                    null,
                    inicio);
        }
        return token;
    }

    private void registrarFallo(String motivo, Integer estado, long inicio) {
        REGISTRO.warn(
                "No se obtuvo el token de servicio en G7. correlacion={} permiso={} motivo={} estado_g7={} duracion_ms={}",
                FiltroCorrelacion.correlacionActual(),
                scopes,
                motivo,
                estado,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio));
    }

    public record Respuesta(
            String accessToken, String tokenType, Long expiresIn, List<String> scopes) {}
}
