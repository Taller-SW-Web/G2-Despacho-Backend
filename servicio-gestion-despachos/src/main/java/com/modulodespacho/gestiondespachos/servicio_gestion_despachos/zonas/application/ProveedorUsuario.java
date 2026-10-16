package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.Optional;
import java.util.UUID;

public interface ProveedorUsuario {

    /** Identificador del ejecutor para la auditoria (claim sub o "sistema"). */
    String identificador();

    /** Identificador del ejecutor cuando es un UUID de Seguridad. */
    Optional<UUID> idUuid();
}
