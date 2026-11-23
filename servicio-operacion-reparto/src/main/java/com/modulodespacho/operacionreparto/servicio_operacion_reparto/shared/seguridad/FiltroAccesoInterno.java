package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.web.FiltroCorrelacion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Simula el enmascaramiento de la URL pública en Render Free: las rutas de negocio {@code
 * /api/v1/**} solo responden si traen el secreto que añade el API Gateway.
 *
 * <p>Se ejecuta antes que Spring Security, así una llamada directa recibe {@code 404} y no revela
 * que la ruta existe. Quedan fuera {@code /actuator/**} (health check de Render), {@code
 * /internal/v1/**} (protegido por el token de servicio), {@code /api/v1/auth/**} y {@code OPTIONS}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@Profile("!integracion-local")
public class FiltroAccesoInterno extends OncePerRequestFilter {

    public static final String ENCABEZADO = "X-Despacho-Interno";
    static final String RUTA_NO_ENCONTRADA = "DESP_ERROR_RUTA_NO_ENCONTRADA";

    private final byte[] secreto;
    private final boolean requerido;
    private final JsonMapper json;

    public FiltroAccesoInterno(
            @Value("${despacho.interno.secreto:}") String secreto,
            @Value("${despacho.interno.requerido:false}") boolean requerido,
            JsonMapper json) {
        String limpio = secreto == null ? "" : secreto.trim();
        if (requerido && limpio.isEmpty()) {
            throw new IllegalStateException(
                    "INTERNO_REQUERIDO=true exige INTERNO_SECRETO no vacío");
        }
        this.secreto = limpio.getBytes(StandardCharsets.UTF_8);
        this.requerido = requerido;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest solicitud) {
        if (!requerido || "OPTIONS".equalsIgnoreCase(solicitud.getMethod())) {
            return true;
        }
        String ruta = solicitud.getRequestURI();
        return !ruta.startsWith("/api/v1/")
                || ruta.equals("/api/v1/auth")
                || ruta.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest solicitud, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String recibido = solicitud.getHeader(ENCABEZADO);
        if (recibido == null
                || !MessageDigest.isEqual(recibido.getBytes(StandardCharsets.UTF_8), secreto)) {
            noEncontrado(solicitud, respuesta);
            return;
        }
        cadena.doFilter(solicitud, respuesta);
    }

    private void noEncontrado(HttpServletRequest solicitud, HttpServletResponse respuesta)
            throws IOException {
        ProblemDetail problema =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Recurso no encontrado");
        problema.setTitle("No encontrado");
        problema.setInstance(URI.create(solicitud.getRequestURI()));
        problema.setProperty("code", RUTA_NO_ENCONTRADA);
        problema.setProperty("correlationId", solicitud.getAttribute(FiltroCorrelacion.ATRIBUTO));
        problema.setProperty("timestamp", Instant.now());
        respuesta.setStatus(HttpStatus.NOT_FOUND.value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.getOutputStream().write(json.writeValueAsBytes(problema));
    }
}
