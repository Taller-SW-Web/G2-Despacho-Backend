package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LectorCatalogoMotivosFallo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.MotivoFallo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

/** Entrada del catálogo para el Gestor de F-04; perfil de campo pendiente de Operación. */
@RestController
@Profile("!integracion-local")
public class ControladorCatalogoMotivosFallo {

    private final LectorCatalogoMotivosFallo catalogo;

    public ControladorCatalogoMotivosFallo(LectorCatalogoMotivosFallo catalogo) {
        this.catalogo = catalogo;
    }

    @GetMapping("/api/v1/catalogos/motivos-fallo")
    @PreAuthorize("hasRole('GESTOR_DESPACHO')")
    public ResponseEntity<RespuestaCatalogo> consultar() {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new RespuestaCatalogo(catalogo.consultarTodos()));
        } catch (DataAccessException error) {
            throw ErrorDeNegocio.de(503, "DESP_ERROR_CONSULTA_NO_DISPONIBLE", "No se pudo consultar el catálogo de motivos");
        }
    }

    public record RespuestaCatalogo(List<MotivoFallo> motivos) {
        public RespuestaCatalogo {
            motivos = List.copyOf(motivos);
        }
    }
}
