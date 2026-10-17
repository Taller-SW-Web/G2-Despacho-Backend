package com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.api;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.application.LectorIncidenciasOperativas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.domain.DatosIncidenciasOperativas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.web.FiltroCorrelacion;

@RestController
@Profile("!integracion-local")
public class ControladorConsultaIncidencias {

    private final LectorIncidenciasOperativas lector;

    public ControladorConsultaIncidencias(LectorIncidenciasOperativas lector) {
        this.lector = lector;
    }

    @PostMapping("/internal/v1/operacion/consulta-incidencias")
    @PreAuthorize("authentication.token.claims['tipo'] == 'servicio' and authentication.name == 'modulo-despacho'"
            + " and hasAuthority('SCOPE_operacion:incidencias:leer')")
    public DatosIncidenciasOperativas consultar(@RequestBody(required = false) Map<String, Object> cuerpo) {
        if (cuerpo == null || !cuerpo.keySet().equals(Set.of("idsRepartidor", "idsJornada"))) {
            throw new IllegalArgumentException("cuerpo");
        }
        List<String> repartidores = identificadores(cuerpo, "idsRepartidor");
        List<String> jornadas = identificadores(cuerpo, "idsJornada");
        if (repartidores.isEmpty() && jornadas.isEmpty()) throw new IllegalArgumentException("cuerpo");
        return lector.consultar(repartidores, jornadas);
    }

    private static List<String> identificadores(Map<String, Object> cuerpo, String campo) {
        if (!(cuerpo.get(campo) instanceof List<?> valores) || valores.size() > 100) {
            throw new IllegalArgumentException(campo);
        }
        List<String> ids = new ArrayList<>();
        Set<String> unicos = new HashSet<>();
        for (Object valor : valores) {
            if (!(valor instanceof String id) || id.isBlank() || !unicos.add(id)) {
                throw new IllegalArgumentException(campo);
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail parametrosInvalidos(IllegalArgumentException error, HttpServletRequest solicitud) {
        ProblemDetail problema = problema(400, "DESP_ERROR_CAMPOS_INVALIDOS", "La consulta por lotes no es válida", solicitud);
        problema.setProperty("errores", List.of(Map.of("campo", error.getMessage(),
                "mensaje", "Se requieren arreglos sin duplicados de hasta 100 identificadores no vacíos")));
        return problema;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail jsonInvalido(HttpMessageNotReadableException error, HttpServletRequest solicitud) {
        return problema(400, "DESP_ERROR_SOLICITUD_INVALIDA", "El cuerpo JSON no es válido", solicitud);
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail lecturaNoDisponible(DataAccessException error, HttpServletRequest solicitud) {
        return problema(503, "DESP_ERROR_OPERACION_NO_DISPONIBLE", "No se pudo consultar la información operativa", solicitud);
    }

    private ProblemDetail problema(int estado, String codigo, String detalle, HttpServletRequest solicitud) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(estado), detalle);
        problema.setTitle("Consulta rechazada");
        problema.setInstance(URI.create(solicitud.getRequestURI()));
        problema.setProperty("code", codigo);
        problema.setProperty("timestamp", Instant.now());
        problema.setProperty("correlationId", solicitud.getAttribute(FiltroCorrelacion.ATRIBUTO));
        return problema;
    }
}
