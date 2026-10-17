package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.api;

import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.infrastructure.ConsultaZonaHttp;

@RestController
@Profile("!integracion-local")
@PreAuthorize("hasRole('GESTOR_DESPACHO')")
public class ControladorJornadas {
    private final GestorJornadas gestor;
    private final ConsultaZonaHttp zonas;
    public ControladorJornadas(GestorJornadas gestor,ConsultaZonaHttp zonas) { this.gestor=gestor;this.zonas=zonas; }
    @PostMapping("/api/v1/jornadas")
    public ResponseEntity<Jornada> abrir(@Valid @RequestBody SolicitudesJornada.Abrir solicitud,@AuthenticationPrincipal Jwt token) {
        UUID actor = actor(token);
        if (!gestor.obtenerFechaActual().equals(solicitud.fecha())) throw ErrorJornada.noApto("La jornada debe abrirse para hoy en America/Bogota.");
        // El HTTP termina antes de tomar bloqueos o comenzar la transacción de escritura.
        zonas.verificar(solicitud.idZona(),token.getTokenValue());
        Jornada resultado=gestor.abrir(solicitud.datos(),actor);
        return ResponseEntity.created(URI.create("/api/v1/jornadas/"+resultado.idJornada())).body(resultado);
    }
    @GetMapping("/api/v1/jornadas/{id}") public Detalle detalle(@PathVariable UUID id) { return gestor.consultarDetalle(id); }
    @PostMapping("/api/v1/jornadas/{id}/cierre")
    public Jornada cerrar(@PathVariable UUID id,@AuthenticationPrincipal Jwt token,@RequestBody(required=false) String cuerpo) {
        if (cuerpo!=null && !cuerpo.isBlank()) throw new ErrorJornada(400,"DESP_ERROR_CAMPOS_INVALIDOS","El cierre no recibe cuerpo.");
        return gestor.cerrar(id,actor(token),"GESTOR_DESPACHO");
    }
    @GetMapping("/api/v1/flota/monitoreo")
    public Monitoreo monitoreo(@RequestParam(required=false) LocalDate fecha,@RequestParam(defaultValue="1") @Min(1) int pagina,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int tamanoPagina,@RequestParam(required=false) String estadoOperativo,
            @RequestParam(required=false) UUID idZona) { return gestor.consultarMonitoreo(fecha,pagina,tamanoPagina,estadoOperativo,idZona); }
    @GetMapping("/api/v1/repartidores/disponibles")
    public Map<String,Object> disponibles(@RequestParam(required=false) LocalDate fecha,@RequestParam(required=false) UUID idZona) {
        return Map.of("repartidores",gestor.consultarDisponibles(fecha,idZona));
    }
    static UUID actor(Jwt token) {
        try { return UUID.fromString(token.getSubject()); }
        catch (IllegalArgumentException|NullPointerException error) { throw new ErrorJornada(403,"DESP_ERROR_IDENTIDAD_INVALIDA","La identidad no permite registrar la operación."); }
    }
}
