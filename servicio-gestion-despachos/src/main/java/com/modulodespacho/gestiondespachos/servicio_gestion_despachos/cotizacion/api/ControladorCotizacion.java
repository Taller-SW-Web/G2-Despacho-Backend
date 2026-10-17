package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.CalcularCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.SolicitudCotizacion;

@RestController
@Profile("integracion-local")
@RequestMapping("/api/v1/cotizaciones")
public class ControladorCotizacion {

    private final CalcularCotizacion calcularCotizacion;

    public ControladorCotizacion(CalcularCotizacion calcularCotizacion) {
        this.calcularCotizacion = calcularCotizacion;
    }

    @PostMapping
    @Operation(summary = "Cotización simulada para validar la integración local",
            description = "Requiere tipo=servicio, audiencia api-despacho y scope cotizaciones:calcular. "
                    + "Solo San Borja tiene cobertura; el catálogo contiene POL-NEG-M y ZAP-RUN-42.")
    public RespuestaCotizacion cotizar(@RequestBody SolicitudCotizacion solicitud) {
        return RespuestaCotizacion.desde(calcularCotizacion.ejecutar(solicitud));
    }
}
