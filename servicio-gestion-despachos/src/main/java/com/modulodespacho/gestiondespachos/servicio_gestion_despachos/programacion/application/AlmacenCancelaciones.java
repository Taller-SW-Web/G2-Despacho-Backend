package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.time.Instant;
import java.util.*;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosCancelacion.*;

public interface AlmacenCancelaciones {
    Intencion preparar(String pedido, UUID clave, Solicitud solicitud, String actor, Instant ahora);
    Optional<Resultado> resultado(UUID id);
    boolean reclamar(UUID id);
    void aplazar(UUID id);
    Resultado confirmar(Intencion intencion, Liberacion liberacion, Instant ahora);
    List<Intencion> recuperables();
}
