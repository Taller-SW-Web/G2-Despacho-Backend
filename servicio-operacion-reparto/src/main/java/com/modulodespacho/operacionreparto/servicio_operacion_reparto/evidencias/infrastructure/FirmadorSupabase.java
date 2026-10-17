package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.FirmadorEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.ErrorEvidencia;

/** Adaptador del bucket privado propuesto en C4. Solo firma lecturas; nunca publica objetos. */
@Component
@Profile("!integracion-local")
public class FirmadorSupabase implements FirmadorEvidencia {
    private final String url;
    private final String bucket;
    private final String clave;
    private final RestClient cliente;
    private final JsonMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    public FirmadorSupabase(@Value("${despacho.evidencias.storage-url:}") String url,
            @Value("${despacho.evidencias.bucket:}") String bucket,
            @Value("${despacho.evidencias.clave-servicio:}") String clave, JsonMapper json) {
        this(url, bucket, clave, json, clienteAcotado());
    }
    FirmadorSupabase(String url, String bucket, String clave, JsonMapper json, RestClient cliente) {
        this.url = url; this.bucket = bucket; this.clave = clave; this.json = json; this.cliente = cliente;
    }
    private static RestClient clienteAcotado() {
        var fabrica = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        fabrica.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().requestFactory(fabrica).build();
    }

    @Override
    public AccesoObjeto firmar(String rutaObjeto, int vigenciaSegundos) {
        try {
            URI origen = URI.create(url);
            if (!"https".equals(origen.getScheme()) || origen.getHost() == null || origen.getRawUserInfo() != null
                    || origen.getRawQuery() != null || origen.getRawFragment() != null
                    || !(origen.getPath().isEmpty() || "/".equals(origen.getPath()))
                    || !bucket.matches("[A-Za-z0-9_-]+") || clave.isBlank() || rutaObjeto == null
                    || Arrays.stream(rutaObjeto.split("/", -1)).anyMatch(parte -> parte.isBlank() || parte.equals(".") || parte.equals(".."))) {
                throw ErrorEvidencia.noDisponible();
            }
            var ruta = UriComponentsBuilder.fromUri(origen).pathSegment("storage", "v1", "object", "sign", bucket)
                    .pathSegment(rutaObjeto.split("/")).build().encode().toUri();
            // Supabase comprueba la existencia del objeto antes de conceder la firma.
            var respuesta = cliente.post().uri(ruta).headers(cabeceras -> {
                cabeceras.setBearerAuth(clave); cabeceras.set("apikey", clave);
            }).contentType(MediaType.APPLICATION_JSON).body(Map.of("expiresIn", vigenciaSegundos))
                    .retrieve().body(RespuestaFirma.class);
            if (respuesta == null || respuesta.signedURL() == null || !respuesta.signedURL().startsWith("/object/sign/")) throw ErrorEvidencia.noDisponible();
            URI firmada = origen.resolve("/storage/v1" + respuesta.signedURL());
            if (!firmada.getRawPath().equals(ruta.getRawPath()) || firmada.getRawFragment() != null) throw ErrorEvidencia.noDisponible();
            String token = UriComponentsBuilder.fromUri(firmada).build().getQueryParams().getFirst("token");
            if (token == null || token.split("\\.").length != 3) throw ErrorEvidencia.noDisponible();
            var datos = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
            if (!datos.path("exp").isIntegralNumber()) throw ErrorEvidencia.noDisponible();
            Instant vence = Instant.ofEpochSecond(datos.path("exp").asLong());
            if (!vence.isAfter(Instant.now()) || vence.isAfter(Instant.now().plusSeconds(vigenciaSegundos + 10L))) throw ErrorEvidencia.noDisponible();
            return new AccesoObjeto(firmada.toString(), vence);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404 || objetoAusente(error)) throw new ErrorEvidencia(404,
                    "DESP_ERROR_EVIDENCIA_NO_ENCONTRADA", "El objeto de evidencia no existe");
            throw ErrorEvidencia.noDisponible();
        } catch (ErrorEvidencia error) { throw error; }
        catch (RuntimeException error) { throw ErrorEvidencia.noDisponible(); }
    }
    private boolean objetoAusente(RestClientResponseException error) {
        try { return "404".equals(json.readTree(error.getResponseBodyAsString()).path("statusCode").asString()); }
        catch (RuntimeException ignorado) { return false; }
    }
    public record RespuestaFirma(String signedURL) {}
}
