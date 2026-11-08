package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoZona;
import java.util.List;

public record SolicitudZona(
        String nombre, EstadoZona estado, List<SolicitudDistritoZona> distritos) {}
