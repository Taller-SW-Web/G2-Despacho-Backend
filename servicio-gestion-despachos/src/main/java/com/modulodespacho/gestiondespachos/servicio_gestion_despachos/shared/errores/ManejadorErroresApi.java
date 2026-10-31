package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores;

import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
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

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorErroresApi.class);

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integridadInvalida(DataIntegrityViolationException error, HttpServletRequest solicitud) {
        LOG.error("Error de integridad en {}", solicitud.getRequestURI(), error);
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return problema(409, CodigosError.CONFLICTO_PERSISTENCIA,
                        "Los datos entran en conflicto con un registro existente", solicitud);
            }
        }
        return problema(500, CodigosError.ERROR_INTERNO,
                "No fue posible guardar la operación", solicitud);
    }

    @ExceptionHandler(ErrorDeNegocio.class)
    ProblemDetail errorDeNegocio(ErrorDeNegocio error, HttpServletRequest solicitud) {
        ProblemDetail problema = problema(error.getEstado(), error.getCodigo(), error.getMessage(), solicitud);
        problema.setTitle("Operación rechazada");
        problema.setProperty("errores", error.getErrores());
        if (error.getEstadoActual() != null) problema.setProperty("estadoActual", error.getEstadoActual());
        return problema;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail cuerpoInvalido(HttpMessageNotReadableException error, HttpServletRequest solicitud) {
        if (solicitud.getRequestURI().endsWith("/devolucion-origen"))
            return errorDeNegocio(com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api.SolicitudesCierre
                    .invalida("solicitud", "El cuerpo JSON no es válido"), solicitud);
        return problema(400, CodigosError.SOLICITUD_INVALIDA, "El cuerpo JSON no es válido", solicitud);
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ProblemDetail persistenciaNoDisponible(org.springframework.dao.DataAccessException error, HttpServletRequest solicitud) {
        return problema(503, "DESP_ERROR_PERSISTENCIA_NO_DISPONIBLE", "No se pudo confirmar la operación; consulta su resultado antes de reintentar", solicitud);
    }

    @ExceptionHandler({jakarta.persistence.OptimisticLockException.class, org.springframework.orm.ObjectOptimisticLockingFailureException.class})
    ProblemDetail versionDesactualizada(Exception error, HttpServletRequest solicitud) {
        return problema(409, "DESP_ERROR_DESPACHO_DESACTUALIZADO", "El despacho fue modificado; vuelve a consultar", solicitud);
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
