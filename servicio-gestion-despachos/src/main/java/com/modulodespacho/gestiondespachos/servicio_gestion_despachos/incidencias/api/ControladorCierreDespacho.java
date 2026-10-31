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
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure.CierreDespachoJdbc;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.ResultadoCierre;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

@RestController
@Profile("!integracion-local")
public class ControladorCierreDespacho {
    private final CierreDespachoJdbc cierres;
    public ControladorCierreDespacho(CierreDespachoJdbc cierres) { this.cierres = cierres; }

    @PostMapping(value = "/api/v1/despachos/{idDespacho}/devolucion-origen", consumes = "application/json")
    @PreAuthorize("hasRole('GESTOR_DESPACHO')")
    public ResponseEntity<ResultadoCierre> cerrar(@PathVariable String idDespacho,
            @RequestBody(required = false) JsonNode cuerpo, @AuthenticationPrincipal Jwt usuario, HttpServletRequest peticion) {
        if (!peticion.getParameterMap().isEmpty()) throw SolicitudesCierre.invalida("parametros", "No admite parámetros de consulta");
        var solicitud = SolicitudesCierre.leer(cuerpo);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(cierres.cerrar(idDespacho, solicitud, usuario.getSubject()));
        } catch (DataAccessException | TransactionException error) {
            throw ErrorDeNegocio.de(503, "DESP_ERROR_CIERRE_NO_DISPONIBLE",
                    "No se pudo confirmar el resultado; consulta el detalle antes de repetir la solicitud original");
        }
    }
}
