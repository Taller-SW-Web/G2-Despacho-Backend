package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.ConsultarEntregasFallidas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.PaginaEntregasFallidas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosIncidenciaInconsistentesException;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

@RestController
@Profile("!integracion-local")
@RequestMapping("/api/v1/despachos/fallidos")
public class ControladorEntregasFallidas {

    private final ConsultarEntregasFallidas consulta;

    public ControladorEntregasFallidas(ConsultarEntregasFallidas consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    @PreAuthorize("hasRole('GESTOR_DESPACHO')")
    public ResponseEntity<PaginaEntregasFallidas> consultar(@RequestParam MultiValueMap<String, String> parametros) {
        var filtros = ParametrosConsultaFallidos.convertir(parametros);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(consulta.consultar(filtros));
        } catch (DataAccessException error) {
            throw ErrorDeNegocio.de(503, "DESP_ERROR_CONSULTA_NO_DISPONIBLE", "No se pudo consultar la información de despachos");
        } catch (DatosIncidenciaInconsistentesException error) {
            throw ErrorDeNegocio.de(500, "DESP_ERROR_DATOS_INCONSISTENTES", "No se pudo obtener una lectura coherente del despacho");
        }
    }
}
