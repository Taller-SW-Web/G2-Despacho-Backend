package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** Límite de cotizaciones por minuto por sub del token de servicio e IP. */
@Component
@Profile("!integracion-local")
public class FiltroLimiteCotizaciones extends OncePerRequestFilter {

    private static final String RUTA = "/api/v1/cotizaciones";

    private final int limitePorMinuto;
    private final JsonMapper json;
    private final ConcurrentHashMap<String, Contador> contadores = new ConcurrentHashMap<>();

    public FiltroLimiteCotizaciones(
            @Value("${despacho.cotizacion.limite-por-minuto:60}") int limitePorMinuto, JsonMapper json) {
        this.limitePorMinuto = limitePorMinuto;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest solicitud) {
        return !"POST".equalsIgnoreCase(solicitud.getMethod()) || !RUTA.equals(solicitud.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest solicitud, HttpServletResponse respuesta,
            FilterChain cadena) throws ServletException, IOException {
        String clave = identificador() + "|" + direccion(solicitud);
        long minuto = Instant.now().getEpochSecond() / 60;
        int conteo = contadores.compute(clave, (ignorado, actual) -> {
            if (actual == null || actual.minuto != minuto) {
                return new Contador(minuto, 1);
            }
            actual.conteo++;
            return actual;
        }).conteo;

        if (conteo > limitePorMinuto) {
            escribirLimite(solicitud, respuesta);
            return;
        }
        cadena.doFilter(solicitud, respuesta);
    }

    private String identificador() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion instanceof JwtAuthenticationToken jwt) {
            String sub = jwt.getToken().getSubject();
            if (sub != null && !sub.isBlank()) {
                return sub;
            }
        }
        return "anonimo";
    }

    private String direccion(HttpServletRequest solicitud) {
        String reenviada = solicitud.getHeader("X-Forwarded-For");
        if (reenviada != null && !reenviada.isBlank()) {
            return reenviada.split(",")[0].trim();
        }
        return solicitud.getRemoteAddr();
    }

    private void escribirLimite(HttpServletRequest solicitud, HttpServletResponse respuesta) throws IOException {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Se superó el límite de cotizaciones por minuto");
        problema.setTitle("Límite de cotizaciones excedido");
        problema.setInstance(URI.create(solicitud.getRequestURI()));
        problema.setProperty("code", CodigosError.LIMITE_COTIZACION);
        problema.setProperty("correlationId", solicitud.getAttribute(FiltroCorrelacion.ATRIBUTO));
        problema.setProperty("timestamp", Instant.now());
        respuesta.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.getOutputStream().write(json.writeValueAsBytes(problema));
    }

    private static final class Contador {
        private final long minuto;
        private int conteo;

        private Contador(long minuto, int conteo) {
            this.minuto = minuto;
            this.conteo = conteo;
        }
    }
}
