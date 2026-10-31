package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.PoliticaCargaEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.VerificadorCargaStorage;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.EvidenciaNoDisponible;

/** Consulta los metadatos del objeto privado con la clave de servicio; no descarga ni publica la imagen. */
@Component
@Profile("!integracion-local")
public class VerificadorCargaSupabase implements VerificadorCargaStorage {
    private final PoliticaCargaEvidencia politica;
    private final RestClient cliente;

    @org.springframework.beans.factory.annotation.Autowired
    public VerificadorCargaSupabase(PoliticaCargaEvidencia politica) { this(politica, clienteAcotado()); }
    VerificadorCargaSupabase(PoliticaCargaEvidencia politica, RestClient cliente) { this.politica = politica; this.cliente = cliente; }
    private static RestClient clienteAcotado() {
        var fabrica = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        fabrica.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(fabrica).build();
    }

    @Override
    public Optional<ObjetoCargado> verificar(String rutaObjeto) {
        if (rutaObjeto == null || Arrays.stream(rutaObjeto.split("/", -1)).anyMatch(parte -> parte.isBlank() || parte.equals(".") || parte.equals("..")))
            throw new EvidenciaNoDisponible("La ruta de la evidencia no es válida.");
        URI origen = origen(politica.urlSupabase());
        URI ruta = UriComponentsBuilder.fromUri(origen).pathSegment("storage", "v1", "object", "authenticated", politica.bucket())
                .pathSegment(rutaObjeto.split("/")).build().encode().toUri();
        try {
            var respuesta = cliente.head().uri(ruta).headers(cabeceras -> {
                cabeceras.setBearerAuth(politica.claveServicio()); cabeceras.set("apikey", politica.claveServicio());
            }).retrieve().toBodilessEntity();
            long bytes = respuesta.getHeaders().getContentLength();
            var tipo = respuesta.getHeaders().getContentType();
            if (bytes <= 0 || tipo == null) throw new EvidenciaNoDisponible("El almacenamiento no informó los metadatos del objeto.");
            return Optional.of(new ObjetoCargado(bytes, tipo.getType() + "/" + tipo.getSubtype()));
        } catch (RestClientResponseException error) {
            // Supabase responde 400 o 404 cuando el objeto aún no existe.
            int estado = error.getStatusCode().value();
            if (estado == 404 || estado == 400) return Optional.empty();
            throw new EvidenciaNoDisponible("No fue posible verificar la carga de evidencia.", error);
        } catch (EvidenciaNoDisponible error) { throw error; }
        catch (RuntimeException error) { throw new EvidenciaNoDisponible("No fue posible verificar la carga de evidencia.", error); }
    }

    private static URI origen(String valor) {
        try {
            URI origen = URI.create(valor);
            boolean local = "http".equalsIgnoreCase(origen.getScheme())
                    && ("localhost".equalsIgnoreCase(origen.getHost()) || "127.0.0.1".equals(origen.getHost()));
            if (!("https".equalsIgnoreCase(origen.getScheme()) || local) || origen.getHost() == null || origen.getRawUserInfo() != null
                    || origen.getRawQuery() != null || origen.getRawFragment() != null
                    || !(origen.getPath().isEmpty() || "/".equals(origen.getPath()))) throw new IllegalArgumentException();
            return URI.create(origen.getScheme() + "://" + origen.getRawAuthority());
        } catch (IllegalArgumentException error) {
            throw new EvidenciaNoDisponible("La URL configurada para Supabase Storage no es válida.", error);
        }
    }
}
