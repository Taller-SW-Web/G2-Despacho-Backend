package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.List;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoZona;

public record SolicitudZona(
        String nombre,
        EstadoZona estado,
        List<SolicitudDistritoZona> distritos) {
}
