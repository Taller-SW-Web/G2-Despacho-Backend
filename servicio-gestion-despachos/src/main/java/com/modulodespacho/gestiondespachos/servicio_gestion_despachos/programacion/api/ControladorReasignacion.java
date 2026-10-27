package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.api;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ReasignarDespacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosReasignacion.Respuesta;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

@RestController
@Profile("!integracion-local")
public class ControladorReasignacion {
    private final ReasignarDespacho reasignacion;
    private final SolicitudesProgramacion solicitudes;
    public ControladorReasignacion(ReasignarDespacho reasignacion, SolicitudesProgramacion solicitudes) {
        this.reasignacion=reasignacion; this.solicitudes=solicitudes;
    }
    @PostMapping(value="/api/v1/despachos/{idDespacho}/reasignacion",consumes="application/json")
    public ResponseEntity<Respuesta> ejecutar(@PathVariable String idDespacho,@RequestBody JsonNode cuerpo,
            @RequestHeader(value="Idempotency-Key",required=false) String clave,
            @RequestHeader(value="If-Match",required=false) String version,
            @AuthenticationPrincipal Jwt identidad,HttpServletRequest solicitud) {
        if (!solicitud.getParameterMap().isEmpty()) throw SolicitudesProgramacion.invalido("parametros","No se admiten parámetros");
        UUID id;
        try { id=UUID.fromString(idDespacho); }
        catch(IllegalArgumentException error) { throw ErrorDeNegocio.de(404,"DESP_ERROR_DESPACHO_NO_ENCONTRADO","El despacho no existe"); }
        int numero;
        try {
            if (version==null || !version.matches("\"[0-9]{1,10}\"")) throw new IllegalArgumentException();
            numero=Integer.parseInt(version.substring(1,version.length()-1));
            if (numero<1) throw new IllegalArgumentException();
        } catch(IllegalArgumentException error) { throw SolicitudesProgramacion.invalido("If-Match","Indica la versión del despacho entre comillas"); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reasignacion.ejecutar(id,
                UUID.fromString(solicitudes.clave(clave)),numero,solicitudes.reasignacion(cuerpo),identidad.getSubject()));
    }
}
