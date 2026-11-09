package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad.DiagnosticoJwt;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RespuestasSeguridad implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger REGISTRO = LoggerFactory.getLogger(RespuestasSeguridad.class);
    private final JsonMapper json;

    public RespuestasSeguridad(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(
            HttpServletRequest solicitud,
            HttpServletResponse respuesta,
            AuthenticationException error)
            throws IOException {
        REGISTRO.warn(
                "Autenticación rechazada en Gestión. ruta={} estado=401 correlacion={} duracion_ms={} {}",
                solicitud.getRequestURI(),
                solicitud.getAttribute(FiltroCorrelacion.ATRIBUTO),
                FiltroCorrelacion.duracionMs(solicitud),
                DiagnosticoJwt.describir(
                        error, solicitud.getHeader(HttpHeaders.AUTHORIZATION) != null));
        respuesta.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        escribir(
                solicitud,
                respuesta,
                HttpStatus.UNAUTHORIZED,
                "DESP_ERROR_TOKEN_INVALIDO",
                "Token no aceptado",
                "No fue posible autenticar la solicitud");
    }

    @Override
    public void handle(
            HttpServletRequest solicitud,
            HttpServletResponse respuesta,
            AccessDeniedException error)
            throws IOException {
        REGISTRO.warn(
                "Acceso denegado en Gestión. ruta={} estado=403 correlacion={}",
                solicitud.getRequestURI(),
                solicitud.getAttribute(FiltroCorrelacion.ATRIBUTO));
        escribir(
                solicitud,
                respuesta,
                HttpStatus.FORBIDDEN,
                "DESP_ERROR_SCOPE_INSUFICIENTE",
                "Permiso insuficiente",
                "El cliente no tiene permiso para realizar esta operación");
    }

    private void escribir(
            HttpServletRequest solicitud,
            HttpServletResponse respuesta,
            HttpStatus estado,
            String codigo,
            String titulo,
            String detalle)
            throws IOException {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setTitle(titulo);
        problema.setInstance(URI.create(solicitud.getRequestURI()));
        problema.setProperty("code", codigo);
        problema.setProperty("correlationId", solicitud.getAttribute(FiltroCorrelacion.ATRIBUTO));
        problema.setProperty("timestamp", Instant.now());
        respuesta.setStatus(estado.value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.getOutputStream().write(json.writeValueAsBytes(problema));
    }
}
