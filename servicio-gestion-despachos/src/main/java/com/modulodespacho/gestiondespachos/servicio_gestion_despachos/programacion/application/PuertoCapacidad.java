package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

public interface PuertoCapacidad {
    ReservaConfirmada reservar(IntencionAsignacion intencion);
    void compensar(String idReserva, String idDespacho);
}
