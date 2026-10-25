package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.transiciones.api;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.transiciones.application.RegistrarTransicion;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.transiciones.application.ModelosTransicion.*;

/** Gestión §6.1: única puerta de Operación hacia el estado canónico; no se publica en el Gateway. */
@RestController
@Profile("!integracion-local")
public class ControladorTransicionesInternas {
    private final RegistrarTransicion transicion;
    public ControladorTransicionesInternas(RegistrarTransicion transicion) { this.transicion = transicion; }

    @PostMapping(value = "/internal/v1/despachos/{idDespacho}/transiciones", consumes = "application/json")
    public ResponseEntity<RespuestaTransicion> registrar(@PathVariable String idDespacho,
            @RequestHeader(value = "Idempotency-Key", required = false) String clave,
            @RequestBody SolicitudTransicion solicitud, @AuthenticationPrincipal Jwt identidad) {
        UUID id;
        try { id = UUID.fromString(idDespacho); }
        catch (IllegalArgumentException error) { throw ErrorDeNegocio.de(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe"); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(transicion.ejecutar(id, clave, solicitud, identidad.getSubject()));
    }
}
