package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.api;

import java.net.URI;
import java.time.Instant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.CotizacionInvalidaException;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;

@RestControllerAdvice(assignableTypes = ControladorCotizacion.class)
@Profile("integracion-local")
public class ManejadorErroresCotizacion {

    @ExceptionHandler(CotizacionInvalidaException.class)
    ProblemDetail cotizacionInvalida(CotizacionInvalidaException error, HttpServletRequest solicitud) {
        return problema(error.getEstado(), error.getCodigo(), error.getMessage(), solicitud);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail cuerpoInvalido(HttpMessageNotReadableException error, HttpServletRequest solicitud) {
        return problema(400, "DESP_ERROR_SOLICITUD_INVALIDA", "El cuerpo JSON no es válido", solicitud);
    }

    private ProblemDetail problema(int estado, String codigo, String detalle, HttpServletRequest solicitud) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(estado), detalle);
        problema.setTitle("Cotización no procesada");
        problema.setInstance(URI.create(solicitud.getRequestURI()));
        problema.setProperty("code", codigo);
        problema.setProperty("correlationId", solicitud.getAttribute(FiltroCorrelacion.ATRIBUTO));
        problema.setProperty("timestamp", Instant.now());
        return problema;
    }
}
