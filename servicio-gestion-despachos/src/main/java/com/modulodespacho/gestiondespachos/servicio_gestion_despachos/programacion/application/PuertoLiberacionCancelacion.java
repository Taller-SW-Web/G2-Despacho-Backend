package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosCancelacion.*;

public interface PuertoLiberacionCancelacion {
    Liberacion liberarPorCancelacion(Intencion intencion);
}
