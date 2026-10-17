package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.api;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.DespachoNoDisponible;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.EvidenciaNoDisponible;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.SolicitudEvidenciaInvalida;

@RestControllerAdvice(assignableTypes = ControladorAutorizacionEvidencia.class)
@Profile("!integracion-local")
public class ErroresEvidencia {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail camposInvalidos(MethodArgumentNotValidException error) {
        return camposInvalidos();
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HandlerMethodValidationException.class})
    public ProblemDetail formatoInvalido(Exception error) {
        return camposInvalidos();
    }

    @ExceptionHandler(SolicitudEvidenciaInvalida.class)
    public ProblemDetail solicitudInvalida(SolicitudEvidenciaInvalida error) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
        problema.setTitle("No fue posible autorizar la evidencia");
        problema.setProperty("code", "DESP_ERROR_CAMPOS_INVALIDOS");
        return problema;
    }

    private ProblemDetail camposInvalidos() {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Revisa los datos requeridos para autorizar la evidencia.");
        problema.setTitle("No fue posible autorizar la evidencia");
        problema.setProperty("code", "DESP_ERROR_CAMPOS_INVALIDOS");
        return problema;
    }

    @ExceptionHandler(DespachoNoDisponible.class)
    public ProblemDetail despachoNoDisponible(DespachoNoDisponible error) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, error.getMessage());
        problema.setTitle("Despacho no disponible");
        problema.setProperty("code", "DESP_ERROR_DESPACHO_NO_ENCONTRADO");
        return problema;
    }

    @ExceptionHandler(EvidenciaNoDisponible.class)
    public ProblemDetail almacenamientoNoDisponible(EvidenciaNoDisponible error) {
        return evidenciaNoDisponible();
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail persistenciaNoDisponible(DataAccessException error) {
        return evidenciaNoDisponible();
    }

    private ProblemDetail evidenciaNoDisponible() {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "No fue posible autorizar la carga de evidencia. Reintenta más tarde.");
        problema.setTitle("Evidencia no disponible");
        problema.setProperty("code", "DESP_ERROR_EVIDENCIA_NO_DISPONIBLE");
        return problema;
    }
}
