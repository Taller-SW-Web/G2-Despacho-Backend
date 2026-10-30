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
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.CancelarDespacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosCancelacion.Respuesta;

@RestController
@Profile("!integracion-local")
public class ControladorCancelacion {
    private final CancelarDespacho cancelacion;
    private final SolicitudesProgramacion solicitudes;
    public ControladorCancelacion(CancelarDespacho cancelacion, SolicitudesProgramacion solicitudes) {
        this.cancelacion = cancelacion; this.solicitudes = solicitudes;
    }
    @PostMapping(value="/api/v1/despachos/pedidos/{idPedido}/cancelacion", consumes="application/json")
    public ResponseEntity<Respuesta> ejecutar(@PathVariable String idPedido, @RequestBody JsonNode cuerpo,
            @RequestHeader(value="Idempotency-Key", required=false) String clave,
            @AuthenticationPrincipal Jwt identidad, HttpServletRequest solicitud) {
        if (!solicitud.getParameterMap().isEmpty()) throw SolicitudesProgramacion.invalido("parametros", "No se admiten parámetros");
        if (idPedido.isBlank() || idPedido.length() > 128 || !idPedido.equals(idPedido.strip()))
            throw SolicitudesProgramacion.invalido("idPedido", "Indica un identificador de pedido válido de hasta 128 caracteres");
        var resultado = cancelacion.ejecutar(idPedido, UUID.fromString(solicitudes.clave(clave)), solicitudes.cancelacion(cuerpo), identidad.getSubject());
        return ResponseEntity.status(resultado.codigoHttp()).cacheControl(CacheControl.noStore()).body(resultado.respuesta());
    }
}
