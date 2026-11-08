package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.configuracion.persistence;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.configuracion.domain.ParametroConfiguracion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParametroConfiguracionRepositorioJpa
        extends JpaRepository<ParametroConfiguracion, String> {}
