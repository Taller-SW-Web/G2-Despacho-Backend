package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.configuracion.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.configuracion.domain.ParametroConfiguracion;

public interface ParametroConfiguracionRepositorioJpa extends JpaRepository<ParametroConfiguracion, String> {
}
