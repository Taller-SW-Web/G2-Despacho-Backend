package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosCancelacion.*;

import java.time.Instant;
import java.util.*;

public interface AlmacenCancelaciones {
    Intencion preparar(String pedido, UUID clave, Solicitud solicitud, String actor, Instant ahora);

    Optional<Resultado> resultado(UUID id);

    boolean reclamar(UUID id);

    void aplazar(UUID id);

    Resultado confirmar(Intencion intencion, Liberacion liberacion, Instant ahora);

    List<Intencion> recuperables();
}
