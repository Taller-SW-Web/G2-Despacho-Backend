package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad.ProveedorTokenServicio;

@Configuration(proxyBeanMethods = false)
@Profile("!integracion-local")
@EnableScheduling
public class ConfiguracionProgramacion {
    @Bean @ConditionalOnMissingBean(Clock.class) Clock relojProgramacion() { return Clock.systemUTC(); }
    @Bean ProductosProgramacionHttp productosProgramacion(Environment entorno, Clock reloj) {
        return new ProductosProgramacionHttp(cliente(entorno.getProperty("despacho.productos.url", "http://127.0.0.1:1")),
                credenciales(entorno, "productos", "productos:fisicos:leer", reloj));
    }
    @Bean CapacidadProgramacionHttp capacidadProgramacion(Environment entorno, Clock reloj) {
        return new CapacidadProgramacionHttp(cliente(entorno.getRequiredProperty("despacho.operacion.url")),
                credenciales(entorno, "operacion", entorno.getProperty("despacho.operacion.scopes-capacidad", ""), reloj));
    }
    @Bean EntregaEventosProgramacion entregaEventosProgramacion(Environment entorno, Clock reloj,
            org.springframework.jdbc.core.simple.JdbcClient sql, tools.jackson.databind.json.JsonMapper json) {
        return new EntregaEventosProgramacion(sql, json,
                cliente(entorno.getProperty("despacho.ventas.url", "http://127.0.0.1:1")),
                credenciales(entorno, "ventas", "ventas:pedidos:estado", reloj),
                cliente(entorno.getRequiredProperty("despacho.operacion.url")),
                credenciales(entorno, "operacion", entorno.getProperty("despacho.operacion.scopes-capacidad", ""), reloj));
    }
    private static ProveedorTokenServicio credenciales(Environment entorno, String dependencia, String scopes, Clock reloj) {
        String prefijo = "despacho." + dependencia + ".";
        return new ProveedorTokenServicio(cliente(entorno.getRequiredProperty("despacho.seguridad.url")),
                entorno.getProperty(prefijo + "cliente-id", "modulo-despacho"), entorno.getProperty(prefijo + "cliente-secreto", ""), scopes,
                () -> entorno.getProperty(prefijo + "token-servicio", ""), reloj);
    }
    private static RestClient cliente(String url) {
        URI origen = URI.create(url);
        Assert.isTrue(("http".equalsIgnoreCase(origen.getScheme()) || "https".equalsIgnoreCase(origen.getScheme()))
                && origen.getHost() != null && origen.getRawUserInfo() == null && origen.getRawQuery() == null
                && origen.getRawFragment() == null && (origen.getPath().isEmpty() || "/".equals(origen.getPath())),
                "La URL de integración debe ser un origen HTTP(S), sin rutas, credenciales ni parámetros");
        var peticiones = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        peticiones.setReadTimeout(Duration.ofMillis(1500));
        return RestClient.builder().baseUrl(url.replaceAll("/+$", "")).requestFactory(peticiones).build();
    }
}
