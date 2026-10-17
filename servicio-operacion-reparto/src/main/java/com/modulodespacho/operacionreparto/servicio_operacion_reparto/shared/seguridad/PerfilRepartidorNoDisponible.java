package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad;

import org.springframework.security.access.AccessDeniedException;

public class PerfilRepartidorNoDisponible extends AccessDeniedException {
    public PerfilRepartidorNoDisponible(Throwable causa) {
        super("No fue posible comprobar el perfil local del repartidor.", causa);
    }
}
