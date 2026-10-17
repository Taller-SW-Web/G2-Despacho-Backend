package com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.api;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.ConflictoFlota;

@RestControllerAdvice(assignableTypes = { ControladorRepartidores.class, ControladorFurgonetas.class })
@Profile("!integracion-local")
public class ErroresFlota {
    @ExceptionHandler(ConflictoFlota.class)
    public ProblemDetail conflicto(ConflictoFlota error) {
        return problema(error.estado(), error.codigo(), error.getMessage());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail campos(MethodArgumentNotValidException error) {
        ProblemDetail problema = invalido();
        problema.setProperty("errores", error.getBindingResult().getFieldErrors().stream()
                .map(campo -> Map.of("campo", campo.getField(), "motivo", "El valor no cumple las restricciones del campo.")).distinct().toList());
        return problema;
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail formato(Exception error) { return invalido(); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail duplicado(DataIntegrityViolationException error) {
        return problema(409, "DESP_ERROR_CONFLICTO_FLOTA", "Los datos están duplicados o incumplen una restricción de flota.");
    }
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail indisponible(DataAccessException error) {
        return problema(503, "DESP_ERROR_OPERACION_NO_DISPONIBLE", "No fue posible completar la operación. Consulta el recurso antes de reintentar.");
    }
    private ProblemDetail invalido() {
        ProblemDetail problema = problema(400, "DESP_ERROR_CAMPOS_INVALIDOS", "Revisa los campos y parámetros de la solicitud.");
        problema.setProperty("errores", List.of());
        return problema;
    }
    private ProblemDetail problema(int estado, String codigo, String detalle) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(estado), detalle);
        problema.setTitle("No fue posible completar la operación de flota");
        problema.setProperty("code", codigo);
        return problema;
    }
}
