package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.web;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCorrelacion extends OncePerRequestFilter {

    public static final String ATRIBUTO = "despacho.correlacion";
    public static final String ENCABEZADO = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest solicitud, HttpServletResponse respuesta,
            FilterChain cadena) throws ServletException, IOException {
        String recibido = solicitud.getHeader(ENCABEZADO);
        String correlacion = recibido != null && recibido.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                ? recibido : UUID.randomUUID().toString();
        solicitud.setAttribute(ATRIBUTO, correlacion);
        respuesta.setHeader(ENCABEZADO, correlacion);
        cadena.doFilter(solicitud, respuesta);
    }
}
