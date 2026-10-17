package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.ConsultarDetalleIncidencia;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.DetalleIncidencia;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosIncidenciaInconsistentesException;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

@RestController
@Profile("!integracion-local")
public class ControladorDetalleIncidencia {
    private final ConsultarDetalleIncidencia consulta;
    public ControladorDetalleIncidencia(ConsultarDetalleIncidencia consulta) { this.consulta = consulta; }

    @GetMapping("/api/v1/despachos/{idDespacho}/incidencia")
    @PreAuthorize("hasRole('GESTOR_DESPACHO')")
    public ResponseEntity<DetalleIncidencia> consultar(@PathVariable String idDespacho, HttpServletRequest solicitud) throws java.io.IOException {
        if (!solicitud.getParameterMap().isEmpty() || solicitud.getInputStream().read() != -1) {
            throw ErrorDeNegocio.de(400, "DESP_ERROR_CONSULTA_INVALIDA", "La consulta no admite cuerpo ni parámetros",
                    java.util.List.of(new ErrorDeCampo("consulta", "Retira el cuerpo y los parámetros de consulta")));
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(consulta.consultar(idDespacho));
        } catch (DataAccessException error) {
            throw ErrorDeNegocio.de(503, "DESP_ERROR_CONSULTA_NO_DISPONIBLE", "No se pudo consultar el detalle del despacho");
        } catch (DatosIncidenciaInconsistentesException error) {
            throw ErrorDeNegocio.de(500, "DESP_ERROR_DATOS_INCONSISTENTES", "No se pudo obtener un detalle coherente del despacho");
        }
    }
}
