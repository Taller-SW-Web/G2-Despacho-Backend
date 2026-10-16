package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.ServicioCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.SolicitudCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

@RestController
@Profile("!integracion-local")
@RequestMapping("/api/v1/cotizaciones")
public class ControladorCotizacionReal {

    private final ServicioCotizacion servicioCotizacion;

    public ControladorCotizacionReal(ServicioCotizacion servicioCotizacion) {
        this.servicioCotizacion = servicioCotizacion;
    }

    @PostMapping
    @Operation(summary = "Cotizar el envío: cobertura y costo con la tarifa vigente de la zona",
            description = "Requiere tipo=servicio, audiencia api-despacho y scope cotizaciones:calcular.")
    public RespuestaCotizacion cotizar(@RequestBody(required = false) SolicitudCotizacion solicitud) {
        if (solicitud == null) {
            throw ErrorDeNegocio.de(400, CodigosError.DESTINO_REQUERIDO,
                    "Debe informar el distrito del destino");
        }
        return RespuestaCotizacion.desde(servicioCotizacion.cotizar(solicitud));
    }
}
