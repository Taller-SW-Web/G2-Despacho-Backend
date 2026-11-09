package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCorrelacion extends OncePerRequestFilter {

    public static final String ATRIBUTO = "despacho.correlacion";
    public static final String ENCABEZADO = "X-Correlation-Id";
    private static final String INICIO = "despacho.inicioSolicitud";

    public static Long duracionMs(HttpServletRequest solicitud) {
        return solicitud.getAttribute(INICIO) instanceof Long inicio
                ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio)
                : null;
    }

    public static String correlacionActual() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes contexto) {
            return (String) contexto.getRequest().getAttribute(ATRIBUTO);
        }
        return null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest solicitud, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String recibido = solicitud.getHeader(ENCABEZADO);
        String correlacion =
                recibido != null
                                && recibido.matches(
                                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                        ? recibido
                        : UUID.randomUUID().toString();
        solicitud.setAttribute(ATRIBUTO, correlacion);
        solicitud.setAttribute(INICIO, System.nanoTime());
        respuesta.setHeader(ENCABEZADO, correlacion);
        cadena.doFilter(solicitud, respuesta);
    }
}
