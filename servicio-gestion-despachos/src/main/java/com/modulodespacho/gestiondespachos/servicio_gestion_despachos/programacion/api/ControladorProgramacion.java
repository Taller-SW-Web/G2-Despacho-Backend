package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.api;

import java.net.URI;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

@RestController
@Profile("!integracion-local")
@RequestMapping("/api/v1/despachos")
public class ControladorProgramacion {
    private final SolicitudesProgramacion solicitudes;
    private final RecibirSolicitudDespacho recepcion;
    private final GenerarDespachoPrueba simulacion;
    private final ConsultarColaPendientes consulta;
    private final AsignarDespacho asignacion;
    public ControladorProgramacion(SolicitudesProgramacion solicitudes, RecibirSolicitudDespacho recepcion,
            GenerarDespachoPrueba simulacion, ConsultarColaPendientes consulta, AsignarDespacho asignacion) {
        this.solicitudes = solicitudes; this.recepcion = recepcion; this.simulacion = simulacion; this.consulta = consulta; this.asignacion = asignacion;
    }
    @PostMapping(consumes = "application/json")
    public ResponseEntity<RespuestaCreacion> recibir(@RequestBody JsonNode cuerpo,
            @RequestHeader(value = "Idempotency-Key", required = false) String clave,
            @AuthenticationPrincipal Jwt identidad, HttpServletRequest solicitud) {
        sinParametros(solicitud);
        String claveValida = solicitudes.clave(clave);
        var resultado = recepcion.ejecutar(solicitudes.recepcion(cuerpo), claveValida, identidad.getSubject());
        return ResponseEntity.status(resultado.codigoHttp()).cacheControl(CacheControl.noStore())
                .location(URI.create("/api/v1/despachos/" + resultado.respuesta().idDespacho())).body(resultado.respuesta());
    }
    @PostMapping(value = "/simulaciones", consumes = "application/json")
    public ResponseEntity<RespuestaCreacion> simular(@RequestBody JsonNode cuerpo, @AuthenticationPrincipal Jwt identidad, HttpServletRequest solicitud) {
        sinParametros(solicitud);
        var resultado = simulacion.ejecutar(solicitudes.simulacion(cuerpo), identidad.getSubject());
        return ResponseEntity.created(URI.create("/api/v1/despachos/" + resultado.respuesta().idDespacho()))
                .cacheControl(CacheControl.noStore()).body(resultado.respuesta());
    }
    @GetMapping("/pendientes")
    public ResponseEntity<PaginaPendientes> pendientes(@RequestParam MultiValueMap<String, String> parametros) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(consulta.ejecutar(ParametrosPendientes.leer(parametros)));
    }
    @PostMapping(value = "/{idDespacho}/asignacion", consumes = "application/json")
    public ResponseEntity<RespuestaAsignacion> asignar(@PathVariable String idDespacho, @RequestBody JsonNode cuerpo,
            @AuthenticationPrincipal Jwt identidad, HttpServletRequest solicitud) {
        sinParametros(solicitud);
        UUID id;
        try { id = UUID.fromString(idDespacho); }
        catch (IllegalArgumentException error) { throw ErrorDeNegocio.de(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe"); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(asignacion.ejecutar(id, solicitudes.asignacion(cuerpo), identidad.getSubject()));
    }
    private static void sinParametros(HttpServletRequest solicitud) {
        if (!solicitud.getParameterMap().isEmpty()) throw SolicitudesProgramacion.invalido("parametros", "Esta operación no admite parámetros de consulta");
    }
}
