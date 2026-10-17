package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.api;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;

@RestController
@Profile("!integracion-local")
public class ControladorCapacidadInterna {
    private final GestorJornadas gestor;
    public ControladorCapacidadInterna(GestorJornadas gestor) { this.gestor=gestor; }
    @PostMapping("/internal/v1/capacidad/reservas")
    public ConfirmacionReserva reservar(@RequestHeader("Idempotency-Key") UUID clave,@Valid @RequestBody SolicitudesJornada.Reservar solicitud,@AuthenticationPrincipal Jwt token) {
        return gestor.reservar(clave,solicitud.datos(),token.getSubject());
    }
    @PostMapping("/internal/v1/capacidad/reservas/{id}/liberacion")
    public Liberacion liberar(@PathVariable UUID id,@Valid @RequestBody SolicitudesJornada.Liberar solicitud,@AuthenticationPrincipal Jwt token) {
        return gestor.liberar(id,solicitud.idDespacho(),solicitud.motivo(),token.getSubject());
    }
    @PostMapping("/internal/v1/proyecciones/despachos/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void proyectar(@PathVariable UUID id,@Valid @RequestBody SolicitudesJornada.Proyectar solicitud,@AuthenticationPrincipal Jwt token) { gestor.proyectar(id,solicitud.datos(),token.getSubject()); }
}
