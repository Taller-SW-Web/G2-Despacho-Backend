package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;

/** Traduce los errores de negocio a RFC 7807 en los perfiles con base de datos real. */
@RestControllerAdvice
@Profile("!integracion-local")
public class ManejadorErroresApi {

    @ExceptionHandler(ErrorDeNegocio.class)
    ProblemDetail errorDeNegocio(ErrorDeNegocio error, HttpServletRequest solicitud) {
        ProblemDetail problema = problema(error.getEstado(), error.getCodigo(), error.getMessage(), solicitud);
        problema.setTitle("Operación rechazada");
        problema.setProperty("errores", error.getErrores());
        return problema;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail cuerpoInvalido(HttpMessageNotReadableException error, HttpServletRequest solicitud) {
        return problema(400, CodigosError.SOLICITUD_INVALIDA, "El cuerpo JSON no es válido", solicitud);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ProblemDetail parametroInvalido(Exception error, HttpServletRequest solicitud) {
        return problema(400, CodigosError.CAMPOS_INVALIDOS,
                "La solicitud contiene parámetros inválidos", solicitud);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail restriccionInvalida(ConstraintViolationException error, HttpServletRequest solicitud) {
        List<ErrorDeCampo> errores = error.getConstraintViolations().stream()
                .map(violacion -> new ErrorDeCampo(violacion.getPropertyPath().toString(), violacion.getMessage()))
                .collect(Collectors.toList());
        ProblemDetail problema = problema(400, CodigosError.CAMPOS_INVALIDOS,
                "La solicitud contiene campos inválidos", solicitud);
        problema.setProperty("errores", errores);
        return problema;
    }

    private ProblemDetail problema(int estado, String codigo, String detalle, HttpServletRequest solicitud) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(estado), detalle);
        problema.setInstance(URI.create(solicitud.getRequestURI()));
        problema.setProperty("code", codigo);
        problema.setProperty("correlationId", solicitud.getAttribute(FiltroCorrelacion.ATRIBUTO));
        problema.setProperty("timestamp", Instant.now());
        return problema;
    }
}
