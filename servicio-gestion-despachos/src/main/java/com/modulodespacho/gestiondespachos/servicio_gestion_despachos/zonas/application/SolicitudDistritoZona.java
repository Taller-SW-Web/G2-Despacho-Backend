package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.List;

public record SolicitudDistritoZona(String distrito, List<String> codigosPostales, Boolean soloCodigosPostales) {
    public SolicitudDistritoZona {
        soloCodigosPostales = Boolean.TRUE.equals(soloCodigosPostales);
    }
}
