package com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.domain.ModelosCampo.*;
import java.util.List;
import java.util.UUID;

/** Gestión es la fuente del estado canónico y del catálogo de motivos; nunca se consultan sus tablas. */
public interface PuertoGestion {
    RespuestaTransicion transicionar(UUID idDespacho, UUID clave, SolicitudTransicion solicitud);

    List<Motivo> motivos();
}
