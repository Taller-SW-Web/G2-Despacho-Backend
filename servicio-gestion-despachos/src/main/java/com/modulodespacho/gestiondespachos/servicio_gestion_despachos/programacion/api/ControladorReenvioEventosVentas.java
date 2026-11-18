package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.api;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure.ReenvioEventosVentas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure.ReenvioEventosVentas.Resultado;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reenvío manual, previsto en el contrato con Ventas (§7.3), de un evento en ENVIO_FALLIDO. */
@RestController
@Profile("!integracion-local")
public class ControladorReenvioEventosVentas {
    private final ReenvioEventosVentas reenvios;

    public ControladorReenvioEventosVentas(ReenvioEventosVentas reenvios) {
        this.reenvios = reenvios;
    }

    @PostMapping("/api/v1/despachos/{idDespacho}/eventos-ventas/{idEvento}/reenvio")
    @PreAuthorize("hasRole('GESTOR_DESPACHO')")
    public ResponseEntity<Resultado> reenviar(
            @PathVariable String idDespacho,
            @PathVariable String idEvento,
            @AuthenticationPrincipal Jwt usuario,
            HttpServletRequest peticion) {
        if (!peticion.getParameterMap().isEmpty())
            throw SolicitudesProgramacion.invalido("parametros", "No se admiten parámetros");
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(
                        reenvios.reenviar(
                                idDespacho,
                                idEvento,
                                usuario == null ? "desconocido" : usuario.getSubject()));
    }
}
