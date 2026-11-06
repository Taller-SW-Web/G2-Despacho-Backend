package com.modulodespacho.api_gateway.seguridad;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Ruta común a los perfiles local/render, independiente de las listas de rutas de negocio. */
@Configuration
public class RutasAccesoSeguridad {
    @Bean
    RouteLocator rutasAccesoHumano(RouteLocatorBuilder constructor,
            @Value("${SEGURIDAD_URL:}") String origen) {
        var rutas = constructor.routes();
        // La demo sin autenticación puede arrancar sin proveedor de identidad configurado.
        // El modo normal sigue exigiendo SEGURIDAD_URL en ConfiguracionSeguridad.
        if (!origen.isBlank()) {
            rutas.route("seguridad-acceso", ruta -> ruta.path("/api/v1/auth/login",
                    "/api/v1/auth/otp/solicitar", "/api/v1/auth/otp/verificar", "/api/v1/auth/refresh",
                    "/api/v1/auth/logout", "/api/v1/auth/me")
                    // Seguridad puede publicar el mismo CORS que ya añadió el Gateway.
                    .filters(filtros -> filtros.dedupeResponseHeader(
                            "Access-Control-Allow-Origin Access-Control-Allow-Credentials", "RETAIN_UNIQUE"))
                    .uri(origen));
        }
        return rutas.build();
    }
}
