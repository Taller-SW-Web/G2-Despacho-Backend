package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.AutorizadorCargaStorage;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.PoliticaCargaEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.EvidenciaNoDisponible;

@Component
@Profile("!integracion-local")
public class AutorizadorCargaSupabase implements AutorizadorCargaStorage {
    private static final Duration VIGENCIA_URL_CARGA = Duration.ofHours(2);

    private final RestClient.Builder clienteBuilder;
    private final PoliticaCargaEvidencia politica;

    public AutorizadorCargaSupabase(RestClient.Builder clienteBuilder, PoliticaCargaEvidencia politica) {
        this.clienteBuilder = clienteBuilder;
        this.politica = politica;
    }

    @Override
    public AutorizacionStorage autorizar(UUID idDespacho, UUID idEvidencia) {
        URI origen = validarOrigen(politica.urlSupabase());
        URI endpoint = UriComponentsBuilder.fromUri(origen)
                .pathSegment("object", "upload", "sign", politica.bucket(),
                        "evidencias", idDespacho.toString(), idEvidencia.toString())
                .build().encode().toUri();

        try {
            RespuestaStorage respuesta = clienteBuilder.build().post().uri(endpoint)
                    .header("apikey", politica.claveServicio())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + politica.claveServicio())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve()
                    .body(RespuestaStorage.class);
            if (respuesta == null || respuesta.url() == null || respuesta.url().isBlank()) {
                throw new EvidenciaNoDisponible("El almacenamiento no devolvió una autorización de carga válida.");
            }
            return new AutorizacionStorage(resolverUrlFirmada(origen, respuesta.url()),
                    Instant.now().plus(VIGENCIA_URL_CARGA));
        } catch (RestClientException error) {
            throw new EvidenciaNoDisponible("No fue posible autorizar la carga de evidencia.", error);
        }
    }

    private record RespuestaStorage(String url) {}

    private URI validarOrigen(String valor) {
        try {
            URI origen = URI.create(valor);
            boolean origenValido = ("https".equalsIgnoreCase(origen.getScheme())
                    || "http".equalsIgnoreCase(origen.getScheme()))
                    && origen.getHost() != null && origen.getRawUserInfo() == null
                    && origen.getRawQuery() == null && origen.getRawFragment() == null
                    && (origen.getPath().isEmpty() || "/".equals(origen.getPath()));
            if (!origenValido || ("http".equalsIgnoreCase(origen.getScheme())
                    && !("localhost".equalsIgnoreCase(origen.getHost()) || "127.0.0.1".equals(origen.getHost())))) {
                throw new IllegalArgumentException();
            }
            return URI.create(origen.getScheme() + "://" + origen.getRawAuthority() + "/storage/v1");
        } catch (IllegalArgumentException error) {
            throw new EvidenciaNoDisponible("La URL configurada para Supabase Storage no es válida.", error);
        }
    }

    private String resolverUrlFirmada(URI origen, String valor) {
        try {
            URI candidata = URI.create(valor);
            URI firmada = candidata.isAbsolute()
                    ? candidata
                    : URI.create(origen.toASCIIString() + (valor.startsWith("/") ? "" : "/") + valor);
            boolean mismaOrigen = firmada.getScheme().equalsIgnoreCase(origen.getScheme())
                    && firmada.getAuthority().equalsIgnoreCase(origen.getAuthority())
                    && firmada.getPath().startsWith(origen.getPath() + "/object/upload/sign/")
                    && firmada.getRawQuery() != null
                    && firmada.getRawQuery().contains("token=");
            if (!mismaOrigen) {
                throw new IllegalArgumentException();
            }
            return firmada.toASCIIString();
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new EvidenciaNoDisponible("El almacenamiento devolvió una URL firmada fuera de su origen esperado.", error);
        }
    }
}
