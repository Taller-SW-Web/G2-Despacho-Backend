package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.seguimientos.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

@Service
@Profile("!integracion-local")
public class ConsultarSeguimientoPedido {
    private final LectorSeguimientoPedido lector;

    public ConsultarSeguimientoPedido(LectorSeguimientoPedido lector) {
        this.lector = lector;
    }

    public SeguimientoPedido consultar(String idPedido) {
        if (idPedido == null || idPedido.isBlank() || idPedido.length() > 128 || !idPedido.equals(idPedido.strip())) {
            throw ErrorDeNegocio.de(400, CodigosError.SOLICITUD_INVALIDA,
                    "Indica un identificador de pedido válido de hasta 128 caracteres");
        }
        return lector.consultar(idPedido);
    }
}
