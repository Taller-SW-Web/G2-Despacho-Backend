package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.api;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;

@RestControllerAdvice(assignableTypes={ControladorJornadas.class,ControladorJornadaPropia.class,ControladorCapacidadInterna.class,
        com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.api.ControladorOperacionCampo.class})
public class ErroresJornadas {
    @ExceptionHandler(ErrorJornada.class) public ProblemDetail negocio(ErrorJornada error) {
        ProblemDetail problema=problema(error.estado(),error.codigo(),error.getMessage());
        if ("DESP_ERROR_CAPACIDAD_EXCEDIDA".equals(error.codigo())) problema.setProperty("errores",error.campos().stream().map(campo -> Map.of("campo",campo,"motivo","Supera la capacidad remanente.")).toList());
        return problema;
    }
    @ExceptionHandler(MethodArgumentNotValidException.class) public ProblemDetail campos(MethodArgumentNotValidException error) {
        var problema=invalido(); problema.setProperty("errores",error.getBindingResult().getFieldErrors().stream()
                .map(campo -> Map.of("campo",campo.getField(),"motivo","El valor no cumple las restricciones.")).distinct().toList());return problema;
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,HandlerMethodValidationException.class,MethodArgumentTypeMismatchException.class,MissingRequestHeaderException.class})
    public ProblemDetail formato(Exception error) { return invalido(); }
    @ExceptionHandler(DataIntegrityViolationException.class) public ProblemDetail integridad(DataIntegrityViolationException error) { return problema(409,"DESP_ERROR_CONFLICTO_JORNADA","Los recursos cambiaron o incumplen una restricción de jornada."); }
    @ExceptionHandler(DataAccessException.class) public ProblemDetail indisponible(DataAccessException error) { return problema(503,"DESP_ERROR_OPERACION_NO_DISPONIBLE","No fue posible confirmar la operación. Consulta el estado antes de repetir."); }
    private ProblemDetail invalido() { var resultado=problema(400,"DESP_ERROR_CAMPOS_INVALIDOS","Revisa el formato y los campos de la solicitud.");resultado.setProperty("errores",List.of());return resultado; }
    private ProblemDetail problema(int estado,String codigo,String mensaje) { var resultado=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(estado),mensaje);resultado.setTitle("No fue posible completar la operación");resultado.setProperty("code",codigo);return resultado; }
}
