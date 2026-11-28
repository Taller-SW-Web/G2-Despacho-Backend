package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.application.PublicadorInterno;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.productos.infrastructure.ClienteDatosFisicosHttp;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad.ProveedorTokenServicio;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@Profile("!integracion-local")
@EnableScheduling
public class ConfiguracionProgramacion {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock relojProgramacion() {
        return Clock.systemUTC();
    }

    @Bean
    ClienteDatosFisicosHttp productosProgramacion(Environment entorno, Clock reloj) {
        // Productos está en otra región (Azure, Chile): 1,5 s de lectura resultó insuficiente.
        // Conexión + lectura deben quedar por debajo del response-timeout del gateway (10 s).
        return new ClienteDatosFisicosHttp(
                cliente(
                        entorno.getProperty("despacho.productos.url", "http://127.0.0.1:1"),
                        Duration.ofMillis(
                                entorno.getProperty(
                                        "despacho.productos.timeout-conexion-ms",
                                        Long.class,
                                        3000L)),
                        Duration.ofMillis(
                                entorno.getProperty(
                                        "despacho.productos.timeout-lectura-ms",
                                        Long.class,
                                        6000L))),
                credenciales(entorno, "productos", "productos:fisicos:leer", reloj),
                entorno.getProperty(
                        "despacho.productos.respaldo-temporal-habilitado", Boolean.class, false));
    }

    @Bean
    CapacidadProgramacionHttp capacidadProgramacion(Environment entorno, Clock reloj) {
        return new CapacidadProgramacionHttp(
                cliente(entorno.getRequiredProperty("despacho.operacion.url")),
                credenciales(entorno, "operacion", "", reloj),
                entorno.getProperty("despacho.demo.sin-autenticacion", Boolean.class, false));
    }

    @Bean
    EntregaEventosProgramacion entregaEventosProgramacion(
            Environment entorno,
            Clock reloj,
            org.springframework.jdbc.core.simple.JdbcClient sql,
            tools.jackson.databind.json.JsonMapper json,
            ObjectProvider<PublicadorInterno> publicador) {
        // Ventas corre en Render free: tras hibernar, 1,5 s de lectura agota los reintentos.
        // El envío es asíncrono (outbox), así que un plazo mayor no afecta a ninguna petición.
        return new EntregaEventosProgramacion(
                sql,
                json,
                cliente(
                        entorno.getProperty("despacho.ventas.url", "http://127.0.0.1:1"),
                        Duration.ofMillis(
                                entorno.getProperty(
                                        "despacho.ventas.timeout-conexion-ms", Long.class, 3000L)),
                        Duration.ofMillis(
                                entorno.getProperty(
                                        "despacho.ventas.timeout-lectura-ms", Long.class, 5000L))),
                credenciales(entorno, "ventas", "ventas:pedidos:estado", reloj),
                cliente(entorno.getRequiredProperty("despacho.operacion.url")),
                credenciales(entorno, "operacion", "", reloj),
                entorno.getProperty("despacho.demo.sin-autenticacion", Boolean.class, false),
                publicador.getIfAvailable());
    }

    private static java.util.function.Supplier<String> credenciales(
            Environment entorno, String dependencia, String scopes, Clock reloj) {
        String prefijo = "despacho." + dependencia + ".";
        if (entorno.getProperty("despacho.demo.sin-autenticacion", Boolean.class, false)) {
            return () -> entorno.getProperty(prefijo + "token-servicio", "");
        }
        return new ProveedorTokenServicio(
                cliente(entorno.getRequiredProperty("despacho.seguridad.url")),
                entorno.getProperty(prefijo + "cliente-id", "modulo-despacho"),
                entorno.getProperty(prefijo + "cliente-secreto", ""),
                scopes,
                () -> entorno.getProperty(prefijo + "token-servicio", ""),
                reloj);
    }

    private static RestClient cliente(String url) {
        return cliente(url, Duration.ofSeconds(1), Duration.ofMillis(1500));
    }

    private static RestClient cliente(String url, Duration conexion, Duration lectura) {
        URI origen = URI.create(url);
        Assert.isTrue(
                ("http".equalsIgnoreCase(origen.getScheme())
                                || "https".equalsIgnoreCase(origen.getScheme()))
                        && origen.getHost() != null
                        && origen.getRawUserInfo() == null
                        && origen.getRawQuery() == null
                        && origen.getRawFragment() == null
                        && (origen.getPath().isEmpty() || "/".equals(origen.getPath())),
                "La URL de integración debe ser un origen HTTP(S), sin rutas, credenciales ni parámetros");
        var peticiones =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(conexion)
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build());
        peticiones.setReadTimeout(lectura);
        return RestClient.builder()
                .baseUrl(url.replaceAll("/+$", ""))
                .requestFactory(peticiones)
                .build();
    }
}
