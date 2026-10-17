package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoZona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.OrigenDelimitacion;

public record ZonaVista(
        UUID id,
        String nombre,
        EstadoZona estado,
        OrigenDelimitacion origenDelimitacion,
        List<DistritoVista> distritos,
        TarifaVista tarifaVigente,
        Instant creadoEn,
        Instant actualizadoEn) {
}
