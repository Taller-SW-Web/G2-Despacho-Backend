package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ConflictoZonaLog;

public interface RegistroConflictos {

    void registrar(ConflictoZonaLog conflicto);
}
