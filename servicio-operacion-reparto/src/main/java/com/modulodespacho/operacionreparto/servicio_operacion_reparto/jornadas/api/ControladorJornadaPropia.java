package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.api;

import org.springframework.context.annotation.Profile;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;

/** El filtro valida el perfil y el caso de uso resuelve siempre la propiedad por sub. */
@RestController
@Profile("!integracion-local")
public class ControladorJornadaPropia {
    private final GestorJornadas gestor;
    public ControladorJornadaPropia(GestorJornadas gestor) { this.gestor=gestor; }
    @GetMapping("/api/v1/repartidor/jornada") public Contexto contexto(@AuthenticationPrincipal Jwt token) { return gestor.consultarContexto(ControladorJornadas.actor(token)); }
    @PostMapping("/api/v1/repartidor/jornada/cierre") public Jornada cerrar(@AuthenticationPrincipal Jwt token,@Valid @RequestBody SolicitudesJornada.CierrePropio solicitud) {
        return gestor.cerrarPropia(ControladorJornadas.actor(token),solicitud.idJornada());
    }
}
