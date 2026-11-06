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
import org.springframework.web.bind.annotation.RequestHeader;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.ComandosConfiguracion;
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
    private final ComandosConfiguracion comandos;

    public ControladorTarifas(GestionTarifas gestionTarifas, ComandosConfiguracion comandos) {
        this.gestionTarifas = gestionTarifas;
        this.comandos = comandos;
    }

    @GetMapping
    @Operation(summary = "Consultar la tarifa vigente de una zona")
    public TarifaVista vigente(@PathVariable UUID idZona) {
        return gestionTarifas.vigente(idZona).orElseThrow(() -> ErrorDeNegocio.de(
                404, CodigosError.TARIFA_NO_ENCONTRADA, "La zona no tiene tarifa vigente"));
    }

    @GetMapping("/historial")
    @Operation(summary = "Consultar el histórico de tarifas de una zona")
    public java.util.List<com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.TarifaHistoricaVista> historial(
            @PathVariable UUID idZona) {
        return gestionTarifas.historial(idZona);
    }

    @PutMapping
    @Operation(summary = "Crear o reemplazar la tarifa vigente de una zona")
    public TarifaVista guardar(@PathVariable UUID idZona, @RequestBody SolicitudTarifa solicitud,
            @RequestHeader(value = "Idempotency-Key", required = false) String clave) {
        return comandos.ejecutar("GUARDAR_TARIFA:" + idZona, solicitud, clave, TarifaVista.class,
                () -> gestionTarifas.guardar(idZona, solicitud));
    }
}
