package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad.ProveedorTokenServicio;

@Configuration(proxyBeanMethods = false)
@Profile("!integracion-local")
@EnableScheduling
public class ConfiguracionRecepcion {
    @Bean(destroyMethod = "close")
    ExecutorService llamadasLiberacion() { return Executors.newVirtualThreadPerTaskExecutor(); }
    @Bean LiberadorCapacidad liberadorCapacidad(Environment entorno, Clock reloj) {
        var token = new ProveedorTokenServicio(cliente(entorno.getRequiredProperty("despacho.seguridad.url")),
                entorno.getProperty("despacho.operacion.cliente-id", "modulo-despacho"),
                entorno.getProperty("despacho.operacion.cliente-secreto", ""), "operacion:capacidad:liberar",
                () -> entorno.getProperty("despacho.operacion.token-servicio", ""), reloj);
        return new LiberadorCapacidadHttp(cliente(entorno.getRequiredProperty("despacho.operacion.url")), token,
                entorno.getProperty("despacho.demo.sin-autenticacion", Boolean.class, false));
    }
    @Bean
    @ConditionalOnProperty(name = "despacho.recepcion.procesador-habilitado", havingValue = "true", matchIfMissing = true)
    ProcesadorLiberaciones procesadorLiberaciones(LiberacionesPendientes pendientes, LiberadorCapacidad operacion,
            Clock reloj, ExecutorService llamadasLiberacion, Environment entorno) {
        var esperas = Arrays.stream(entorno.getProperty("despacho.recepcion.esperas", "PT30S,PT2M,PT10M,PT30M,PT1H").split(","))
                .map(String::strip).map(Duration::parse).toList();
        return new ProcesadorLiberaciones(pendientes, operacion, reloj, llamadasLiberacion, esperas);
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
