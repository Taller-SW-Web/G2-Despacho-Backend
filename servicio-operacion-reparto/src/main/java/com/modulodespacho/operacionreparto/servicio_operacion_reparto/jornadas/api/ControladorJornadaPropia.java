package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.api;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application.OperacionCampo;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;

/** El filtro valida el perfil y el caso de uso resuelve siempre la propiedad por sub. */
@RestController
@Profile("!integracion-local")
public class ControladorJornadaPropia {
    private final GestorJornadas gestor;
    private final OperacionCampo campo;
    public ControladorJornadaPropia(GestorJornadas gestor,OperacionCampo campo) { this.gestor=gestor;this.campo=campo; }
    @GetMapping("/api/v1/repartidor/jornada") public ResponseEntity<Contexto> contexto(@AuthenticationPrincipal Jwt token) {
        return sinCache(gestor.consultarContexto(ControladorJornadas.actor(token)));
    }
    @GetMapping("/api/v1/repartidor/mi-ruta") public ResponseEntity<RutaRepartidor> ruta(@AuthenticationPrincipal Jwt token) {
        return sinCache(gestor.consultarRuta(ControladorJornadas.actor(token)));
    }
    @GetMapping("/api/v1/repartidor/despachos/{idDespacho}") public ResponseEntity<DetalleDespacho> despacho(
            @PathVariable UUID idDespacho,@AuthenticationPrincipal Jwt token) {
        return sinCache(gestor.consultarDespachoPropio(ControladorJornadas.actor(token),idDespacho));
    }
    @GetMapping("/api/v1/repartidor/jornada/resumen") public ResponseEntity<ResumenJornada> resumen(@AuthenticationPrincipal Jwt token) {
        return sinCache(gestor.consultarResumenPropio(ControladorJornadas.actor(token)));
    }
    /** ES-F03-07: los pendientes quedan FALLIDO/NO_INTENTADO en Gestión antes de cerrar el turno. */
    @PostMapping("/api/v1/repartidor/jornada/cierre") public ResponseEntity<Jornada> cerrar(@AuthenticationPrincipal Jwt token,
            @RequestHeader("Idempotency-Key") UUID clave,@Valid @RequestBody SolicitudesJornada.CierrePropio solicitud) {
        if (clave.version() != 4) throw new ErrorJornada(400,"DESP_ERROR_CAMPOS_INVALIDOS","Idempotency-Key debe ser UUID v4.");
        return sinCache(campo.cerrarJornada(ControladorJornadas.actor(token),solicitud.idJornada(),clave));
    }
    private static <T> ResponseEntity<T> sinCache(T cuerpo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cuerpo);
    }
}
