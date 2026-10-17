package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.api;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionTarifas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.SolicitudTarifa;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.TarifaVista;

@RestController
@Profile("!integracion-local")
@RequestMapping("/api/v1/zonas/{idZona}/tarifa")
public class ControladorTarifas {

    private final GestionTarifas gestionTarifas;

    public ControladorTarifas(GestionTarifas gestionTarifas) {
        this.gestionTarifas = gestionTarifas;
    }

    @GetMapping
    @Operation(summary = "Consultar la tarifa vigente de una zona")
    public TarifaVista vigente(@PathVariable UUID idZona) {
        return gestionTarifas.vigente(idZona).orElseThrow(() -> ErrorDeNegocio.de(
                404, CodigosError.TARIFA_NO_ENCONTRADA, "La zona no tiene tarifa vigente"));
    }

    @PutMapping
    @Operation(summary = "Crear o reemplazar la tarifa vigente de una zona")
    public TarifaVista guardar(@PathVariable UUID idZona, @RequestBody SolicitudTarifa solicitud) {
        return gestionTarifas.guardar(idZona, solicitud);
    }
}
