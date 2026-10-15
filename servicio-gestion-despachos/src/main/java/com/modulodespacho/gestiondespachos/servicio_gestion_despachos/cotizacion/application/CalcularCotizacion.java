package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.ResultadoCotizacion;

@Service
@Profile("integracion-local")
public class CalcularCotizacion {

    private final PuertoCotizacion cotizador;

    public CalcularCotizacion(PuertoCotizacion cotizador) {
        this.cotizador = cotizador;
    }

    public ResultadoCotizacion ejecutar(SolicitudCotizacion solicitud) {
        if (solicitud.destino() == null || solicitud.destino().distrito() == null
                || solicitud.destino().distrito().isBlank()) {
            throw new CotizacionInvalidaException("DESP_ERROR_DESTINO_REQUERIDO",
                    "Debe informar el distrito del destino", 400);
        }
        // Sin cobertura, el contrato exige responder antes de validar las líneas.
        if (cotizador.tieneCobertura(solicitud.destino().distrito()) && solicitud.lineas() != null) {
            if (solicitud.lineas().isEmpty()) {
                throw new CotizacionInvalidaException("DESP_ERROR_LINEAS_VACIAS",
                        "Las líneas deben contener al menos un producto", 400);
            }
            for (SolicitudCotizacion.Linea linea : solicitud.lineas()) {
                if (linea == null || linea.sku() == null || linea.sku().isBlank()) {
                    throw new CotizacionInvalidaException("DESP_ERROR_SOLICITUD_INVALIDA",
                            "Cada línea debe indicar un SKU", 400);
                }
                if (linea.cantidad() == null || linea.cantidad() <= 0) {
                    throw new CotizacionInvalidaException("DESP_ERROR_CANTIDAD_INVALIDA",
                            "La cantidad debe ser mayor que cero", 400);
                }
            }
        }
        return cotizador.cotizar(solicitud);
    }
}
