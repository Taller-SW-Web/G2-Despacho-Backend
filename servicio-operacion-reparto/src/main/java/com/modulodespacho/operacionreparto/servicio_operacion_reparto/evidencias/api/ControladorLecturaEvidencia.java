package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.AutorizarLecturaEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.ErrorEvidencia;

@RestController
@Profile("!integracion-local")
public class ControladorLecturaEvidencia {
    private final AutorizarLecturaEvidencia autorizacion;
    public ControladorLecturaEvidencia(AutorizarLecturaEvidencia autorizacion) { this.autorizacion = autorizacion; }

    @PostMapping("/api/v1/evidencias/{idEvidencia}/acceso")
    public ResponseEntity<AutorizarLecturaEvidencia.AccesoEvidencia> autorizar(@PathVariable String idEvidencia,
            Authentication identidad, HttpServletRequest solicitud) throws java.io.IOException {
        if (!solicitud.getParameterMap().isEmpty() || solicitud.getInputStream().read() != -1) throw new ErrorEvidencia(400,
                "DESP_ERROR_CONSULTA_INVALIDA", "La autorización no admite cuerpo ni parámetros");
        boolean gestor = identidad.getAuthorities().stream().anyMatch(permiso -> "ROLE_GESTOR_DESPACHO".equals(permiso.getAuthority()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(autorizacion.autorizar(idEvidencia, identidad.getName(), gestor));
    }

    @ExceptionHandler({ErrorEvidencia.class, DataAccessException.class})
    ResponseEntity<ProblemDetail> problema(RuntimeException error, HttpServletRequest solicitud) {
        var evidencia = error instanceof ErrorEvidencia propio ? propio : ErrorEvidencia.noDisponible();
        var problema = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(evidencia.estado()), evidencia.getMessage());
        problema.setTitle("No se pudo consultar la evidencia");
        problema.setInstance(java.net.URI.create(solicitud.getRequestURI()));
        problema.setProperty("code", evidencia.codigo());
        problema.setProperty("timestamp", Instant.now());
        problema.setProperty("correlationId", solicitud.getAttribute("correlationId"));
        problema.setProperty("errores", evidencia.estado() == 400 ? List.of(Map.of("campo", "consulta", "motivo", "Retira cuerpo y parámetros")) : List.of());
        return ResponseEntity.status(evidencia.estado()).cacheControl(CacheControl.noStore()).body(problema);
    }
}
