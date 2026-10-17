package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.ZonaDisponible;

public interface CoberturaProgramacion {
    ZonaDisponible resolver(String distrito, String codigoPostal);
    ZonaDisponible paraSimulacion(String idZona);
}
