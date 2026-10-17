package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.CatalogoMotivosFallo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.ConsultarEntregasFallidas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LectorDatosOperativos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LectorIncidenciasLocales;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.ConsultarDetalleIncidencia;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LectorDetalleIncidencia;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@Profile("!integracion-local")
public class ConfiguracionConsultaFallidos {

    @Bean(destroyMethod = "close")
    ExecutorService consultasDetalle() { return Executors.newVirtualThreadPerTaskExecutor(); }

    @Bean
    ConsultarDetalleIncidencia consultarDetalleIncidencia(LectorDetalleIncidencia locales,
            LectorDatosOperativos operacion, ExecutorService consultasDetalle) {
        return new ConsultarDetalleIncidencia(locales, operacion, Clock.systemUTC(), consultasDetalle);
    }

    @Bean
    LectorDatosOperativos lectorDatosOperativos(@Value("${despacho.operacion.url}") String url, Environment entorno) {
        URI origen = URI.create(url);
        Assert.isTrue(("http".equalsIgnoreCase(origen.getScheme()) || "https".equalsIgnoreCase(origen.getScheme()))
                        && origen.getHost() != null && origen.getRawUserInfo() == null
                        && origen.getRawQuery() == null && origen.getRawFragment() == null
                        && (origen.getPath().isEmpty() || origen.getPath().equals("/")),
                "OPERACION_REPARTO_URL debe ser un origen HTTP(S) sin rutas, credenciales ni parámetros");
        JdkClientHttpRequestFactory peticiones = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build());
        peticiones.setReadTimeout(Duration.ofSeconds(2));
        RestClient cliente = RestClient.builder().baseUrl(url.replaceAll("/+$", "")).requestFactory(peticiones).build();
        RestClient seguridad = RestClient.builder().baseUrl(entorno.getRequiredProperty("despacho.seguridad.url")
                .replaceAll("/+$", "")).requestFactory(peticiones).build();
        ProveedorTokenOperacion credenciales = new ProveedorTokenOperacion(seguridad,
                entorno.getProperty("despacho.operacion.cliente-id", "modulo-despacho"),
                () -> entorno.getProperty("despacho.operacion.cliente-secreto", ""),
                () -> entorno.getProperty("despacho.operacion.token-servicio", ""), Clock.systemUTC());
        return new OperacionIncidenciasHttp(cliente, credenciales);
    }

    @Bean
    ConsultarEntregasFallidas consultarEntregasFallidas(LectorIncidenciasLocales locales,
            LectorDatosOperativos operacion, CatalogoMotivosFallo motivos) {
        return new ConsultarEntregasFallidas(locales, operacion, motivos, Clock.systemUTC());
    }
}
