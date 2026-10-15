package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.ResultadoCotizacion;

public interface PuertoCotizacion {
    boolean tieneCobertura(String distrito);
    ResultadoCotizacion cotizar(SolicitudCotizacion solicitud);
}
