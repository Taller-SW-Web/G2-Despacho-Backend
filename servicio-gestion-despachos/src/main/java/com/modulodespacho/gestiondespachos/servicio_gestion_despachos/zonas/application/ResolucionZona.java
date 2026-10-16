package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.UUID;

public record ResolucionZona(boolean coberturaDisponible, UUID idZona, String nombreZona) {

    public static ResolucionZona sinCobertura() {
        return new ResolucionZona(false, null, null);
    }

    public static ResolucionZona cubierto(UUID idZona, String nombreZona) {
        return new ResolucionZona(true, idZona, nombreZona);
    }
}
