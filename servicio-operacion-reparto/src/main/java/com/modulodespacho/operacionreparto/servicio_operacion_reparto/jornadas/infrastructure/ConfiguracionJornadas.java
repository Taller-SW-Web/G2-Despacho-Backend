package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
@Profile("!integracion-local")
public class ConfiguracionJornadas {
    @Bean @ConditionalOnMissingBean(Clock.class) Clock relojJornadas() { return Clock.systemUTC(); }
    @Bean ConsultaZonaHttp consultaZona(@Value("${despacho.gestion.url}") String url) {
        URI origen = URI.create(url);
        Assert.isTrue(("https".equalsIgnoreCase(origen.getScheme()) || "http".equalsIgnoreCase(origen.getScheme())) && origen.getHost()!=null
                && origen.getRawUserInfo()==null && origen.getRawQuery()==null && origen.getRawFragment()==null
                && (origen.getPath().isEmpty() || "/".equals(origen.getPath())),"GESTION_DESPACHOS_URL debe ser un origen HTTP(S) sin ruta ni credenciales.");
        var peticiones = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
        peticiones.setReadTimeout(Duration.ofSeconds(5));
        return new ConsultaZonaHttp(RestClient.builder().baseUrl(url.replaceAll("/+$", "")).requestFactory(peticiones).build());
    }
}
