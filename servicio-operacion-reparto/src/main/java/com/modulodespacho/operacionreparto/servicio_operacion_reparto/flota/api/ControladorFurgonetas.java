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
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.DatosFlota.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.api.SolicitudesFlota.*;

@RestController
@RequestMapping("/api/v1/furgonetas")
@Profile("!integracion-local")
@PreAuthorize("hasRole('GESTOR_DESPACHO')")
public class ControladorFurgonetas {
    private final GestorFlota gestor;
    public ControladorFurgonetas(GestorFlota gestor) { this.gestor = gestor; }
    @GetMapping
    public Pagina<Furgoneta> listar(@RequestParam(defaultValue = "1") @Min(1) int pagina,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int tamanoPagina,
            @RequestParam(required = false) EstadoFurgoneta estado,
            @RequestParam(required = false) @Size(max = 10) String placa) {
        return gestor.listarFurgonetas(pagina, tamanoPagina, estado, placa);
    }
    @PostMapping
    public ResponseEntity<Furgoneta> registrar(@RequestBody @Valid NuevaFurgoneta solicitud, @AuthenticationPrincipal Jwt token) {
        Furgoneta resultado = gestor.registrarFurgoneta(solicitud.placa(), solicitud.datos(), ControladorRepartidores.actor(token));
        return ResponseEntity.created(URI.create("/api/v1/furgonetas/" + resultado.idFurgoneta())).body(resultado);
    }
    @PutMapping("/{id}")
    public Furgoneta editar(@PathVariable UUID id, @RequestBody @Valid EditarFurgoneta solicitud, @AuthenticationPrincipal Jwt token) {
        return gestor.editarFurgoneta(id, solicitud.datos(), ControladorRepartidores.actor(token));
    }
    @PatchMapping("/{id}/estado")
    public ResponseEntity<Void> cambiarEstado(@PathVariable UUID id, @RequestBody @Valid CambiarEstado solicitud, @AuthenticationPrincipal Jwt token) {
        gestor.cambiarEstado(id, solicitud.estado(), ControladorRepartidores.actor(token));
        return ResponseEntity.noContent().build();
    }
}
