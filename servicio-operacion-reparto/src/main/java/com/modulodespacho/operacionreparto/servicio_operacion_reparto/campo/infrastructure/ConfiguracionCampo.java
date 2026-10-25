package com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad.ProveedorTokenServicio;

@Configuration(proxyBeanMethods = false)
@Profile("!integracion-local")
public class ConfiguracionCampo {
    @Bean GestionHttp gestionCampo(Environment entorno, Clock reloj, JsonMapper json) {
        boolean demo = entorno.getProperty("despacho.demo.sin-autenticacion", Boolean.class, false);
        java.util.function.Supplier<String> credencial = demo
                ? () -> entorno.getProperty("despacho.gestion.token-servicio", "")
                : new ProveedorTokenServicio(cliente(entorno.getRequiredProperty("despacho.seguridad.url"), Duration.ofSeconds(3)),
                        entorno.getProperty("despacho.gestion.cliente-id", "modulo-despacho"),
                        entorno.getProperty("despacho.gestion.cliente-secreto", ""),
                        entorno.getProperty("despacho.gestion.scopes", "gestion:despachos:transicionar gestion:catalogos:leer"),
                        () -> entorno.getProperty("despacho.gestion.token-servicio", ""), reloj);
        // ES-F03-04 RN-09: la transición responde en menos de 2 s; el plazo acotado evita sostener la solicitud.
        return new GestionHttp(cliente(entorno.getRequiredProperty("despacho.gestion.url"), Duration.ofMillis(1500)), credencial, demo, json);
    }
    private static RestClient cliente(String url, Duration lectura) {
        URI origen = URI.create(url);
        Assert.isTrue(("http".equalsIgnoreCase(origen.getScheme()) || "https".equalsIgnoreCase(origen.getScheme()))
                && origen.getHost() != null && origen.getRawUserInfo() == null && origen.getRawQuery() == null
                && origen.getRawFragment() == null && (origen.getPath().isEmpty() || "/".equals(origen.getPath())),
                "La URL de integración debe ser un origen HTTP(S), sin rutas, credenciales ni parámetros");
        var peticiones = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        peticiones.setReadTimeout(lectura);
        return RestClient.builder().baseUrl(url.replaceAll("/+$", "")).requestFactory(peticiones).build();
    }
}
