package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RespuestasSeguridad implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper json;

    public RespuestasSeguridad(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest solicitud, HttpServletResponse respuesta,
            AuthenticationException error) throws IOException {
        respuesta.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        escribir(solicitud, respuesta, HttpStatus.UNAUTHORIZED, "DESP_ERROR_TOKEN_INVALIDO",
                "Token no aceptado", "No fue posible autenticar la solicitud");
    }

    @Override
    public void handle(HttpServletRequest solicitud, HttpServletResponse respuesta,
            AccessDeniedException error) throws IOException {
        escribir(solicitud, respuesta, HttpStatus.FORBIDDEN, "DESP_ERROR_SCOPE_INSUFICIENTE",
                "Permiso insuficiente", "El cliente no tiene permiso para realizar esta operación");
    }

    private void escribir(HttpServletRequest solicitud, HttpServletResponse respuesta,
            HttpStatus estado, String codigo, String titulo, String detalle) throws IOException {
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
