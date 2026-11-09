package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.api;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionZonas;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ControladorZonas.class)
@Profile("!integracion-local")
@Order(0)
public class ManejadorConcurrenciaZonas {
    private final org.springframework.beans.factory.ObjectProvider<GestionZonas> zonas;

    public ManejadorConcurrenciaZonas(
            org.springframework.beans.factory.ObjectProvider<GestionZonas> zonas) {
        this.zonas = zonas;
    }

    @ExceptionHandler({
        jakarta.persistence.OptimisticLockException.class,
        org.springframework.orm.ObjectOptimisticLockingFailureException.class
    })
    ProblemDetail desactualizada(Exception error, HttpServletRequest solicitud) {
        var actual =
                zonas.getObject().obtener(UUID.fromString(solicitud.getRequestURI().split("/")[4]));
        ProblemDetail problema =
                ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "La zona fue modificada; vuelve a consultar");
        problema.setInstance(URI.create(solicitud.getRequestURI()));
        problema.setProperty("code", "DESP_ERROR_ZONA_DESACTUALIZADA");
        problema.setProperty("estadoActual", actual.estado());
        problema.setProperty("versionZona", actual.versionZona());
        problema.setProperty("correlationId", solicitud.getAttribute(FiltroCorrelacion.ATRIBUTO));
        problema.setProperty("timestamp", Instant.now());
        return problema;
    }
}
