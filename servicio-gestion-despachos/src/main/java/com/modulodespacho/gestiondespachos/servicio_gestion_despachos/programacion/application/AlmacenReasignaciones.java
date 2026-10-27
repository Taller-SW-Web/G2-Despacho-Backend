package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.time.Instant;
import java.util.*;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosReasignacion.*;

public interface AlmacenReasignaciones {
    Intencion preparar(UUID despacho, UUID clave, int version, Solicitud solicitud, String actor);
    Optional<Respuesta> resultado(UUID id);
    Optional<Rechazo> rechazo(UUID id);
    boolean reclamar(UUID id);
    void aplazar(UUID id);
    void rechazar(UUID id, Rechazo rechazo);
    Respuesta confirmar(Intencion intencion, Transferencia transferencia, Instant ahora);
    List<Intencion> recuperables();
}
