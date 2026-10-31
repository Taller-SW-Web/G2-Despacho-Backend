package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure.ReprogramacionDespachoJdbc;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.ResultadoReprogramacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

@RestController
@Profile("!integracion-local")
public class ControladorReprogramacion {
    private final ReprogramacionDespachoJdbc reprogramaciones;
    public ControladorReprogramacion(ReprogramacionDespachoJdbc reprogramaciones) { this.reprogramaciones = reprogramaciones; }

    @PostMapping(value = "/api/v1/despachos/{idDespacho}/reprogramacion", consumes = "application/json")
    @PreAuthorize("hasRole('GESTOR_DESPACHO')")
    public ResponseEntity<ResultadoReprogramacion> reprogramar(@PathVariable String idDespacho,
            @RequestBody(required = false) JsonNode cuerpo, @AuthenticationPrincipal Jwt usuario, HttpServletRequest peticion) {
        if (!peticion.getParameterMap().isEmpty()) throw SolicitudesReprogramacion.invalida("parametros", "No admite parámetros de consulta");
        var solicitud = SolicitudesReprogramacion.leer(cuerpo);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(reprogramaciones.reprogramar(idDespacho, solicitud, usuario.getSubject()));
        } catch (DataAccessException | TransactionException error) {
            throw ErrorDeNegocio.de(503, "DESP_ERROR_REPROGRAMACION_NO_DISPONIBLE",
                    "No se pudo confirmar el resultado; consulta el detalle antes de repetir la solicitud original");
        }
    }
}
