package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.CalculadoraCotizacion.Totales;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.DatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.ResultadoCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.TarifaAplicada;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InstantaneaCotizacion(
        UUID idZona,
        TarifaAplicada tarifa,
        SolicitudCotizacion.Destino destino,
        List<LineaCotizada> lineas,
        Totales totalesExactos,
        BigDecimal costoExacto,
        BigDecimal recargoPorPeso,
        BigDecimal ajusteRedondeo,
        ResultadoCotizacion resultado,
        String consumidor,
        boolean simulado) {

    public InstantaneaCotizacion {
        lineas = List.copyOf(lineas);
    }

    public record LineaCotizada(String sku, int cantidad, DatosFisicos perfil) {}
}
