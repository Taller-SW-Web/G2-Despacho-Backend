package com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.api;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.application.GestorFlota;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.ConflictoFlota;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.DatosFlota.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.api.SolicitudesFlota.*;

@RestController
@RequestMapping("/api/v1/repartidores")
@Profile("!integracion-local")
@PreAuthorize("hasRole('GESTOR_DESPACHO')")
public class ControladorRepartidores {
    private final GestorFlota gestor;
    public ControladorRepartidores(GestorFlota gestor) { this.gestor = gestor; }
    @GetMapping
    public Pagina<Repartidor> listar(@RequestParam(defaultValue = "1") @Min(1) int pagina,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int tamanoPagina,
            @RequestParam(required = false) EstadoRegistro estadoRegistro,
            @RequestParam(required = false) Vinculacion estadoVinculacion,
            @RequestParam(required = false) Turno turnoHabitual) {
        return gestor.listarRepartidores(pagina, tamanoPagina, estadoRegistro, estadoVinculacion, turnoHabitual);
    }
    @GetMapping("/{id}")
    public Repartidor consultar(@PathVariable UUID id) { return gestor.obtenerRepartidor(id); }
    @PostMapping
    public ResponseEntity<Repartidor> registrar(@RequestBody @Valid NuevoRepartidor solicitud, @AuthenticationPrincipal Jwt token) {
        Repartidor resultado = gestor.registrarRepartidor(solicitud.datos(), actor(token));
        return ResponseEntity.created(URI.create("/api/v1/repartidores/" + resultado.idRepartidor())).body(resultado);
    }
    @PutMapping("/{id}")
    public Repartidor editar(@PathVariable UUID id, @RequestBody @Valid EditarRepartidor solicitud, @AuthenticationPrincipal Jwt token) {
        return gestor.editarRepartidor(id, solicitud.datos(), actor(token));
    }
    @PatchMapping("/{id}/estado")
    public ResponseEntity<Void> darDeBaja(@PathVariable UUID id, @RequestBody @Valid Baja solicitud, @AuthenticationPrincipal Jwt token) {
        gestor.darDeBaja(id, actor(token));
        return ResponseEntity.noContent().build();
    }
    @PutMapping("/{id}/vinculacion")
    public ResponseEntity<Void> vincular(@PathVariable UUID id, @RequestBody @Valid Vincular solicitud, @AuthenticationPrincipal Jwt token) {
        gestor.vincular(id, solicitud.usuarioId(), actor(token));
        return ResponseEntity.noContent().build();
    }
    static UUID actor(Jwt token) {
        try { return UUID.fromString(token.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ConflictoFlota(403, "DESP_ERROR_IDENTIDAD_INVALIDA", "La identidad autenticada no permite registrar auditoría.");
        }
    }
}
