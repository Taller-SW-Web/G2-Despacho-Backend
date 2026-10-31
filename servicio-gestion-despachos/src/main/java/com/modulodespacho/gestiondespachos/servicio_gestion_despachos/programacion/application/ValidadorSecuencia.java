package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.util.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosRuta.*;

/** Permuta los espacios vigentes; nunca renumera posiciones históricas de paradas iniciadas. */
public final class ValidadorSecuencia {
    private ValidadorSecuencia() {}
    public static Map<UUID, Integer> validar(List<Parada> actuales, Secuencia solicitud) {
        Map<UUID, Integer> nuevas = new HashMap<>();
        Set<Integer> posiciones = new HashSet<>();
        for (Posicion parada : solicitud.despachos()) {
            if (nuevas.put(parada.idDespacho(), parada.posicion()) != null || !posiciones.add(parada.posicion())) throw invalida();
        }
        if (nuevas.size() != actuales.size() || !nuevas.keySet().equals(actuales.stream().map(Parada::idDespacho).collect(java.util.stream.Collectors.toSet()))
                || !posiciones.equals(actuales.stream().map(Parada::posicion).collect(java.util.stream.Collectors.toSet()))) throw invalida();
        for (Parada parada : actuales) if (!"ASIGNADO".equals(parada.estado()) && nuevas.get(parada.idDespacho()) != parada.posicion())
            throw ErrorDeNegocio.de(409, "DESP_ERROR_ESTADO_INCOMPATIBLE", "Una parada iniciada o cerrada no puede cambiar de posición");
        return nuevas;
    }
    private static ErrorDeNegocio invalida() { return ErrorDeNegocio.de(400, "DESP_ERROR_SECUENCIA_INVALIDA", "Secuencia de ruta inválida"); }
}
