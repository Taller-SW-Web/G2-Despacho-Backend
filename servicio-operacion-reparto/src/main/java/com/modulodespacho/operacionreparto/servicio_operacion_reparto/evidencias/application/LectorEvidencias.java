package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application;

import java.util.Optional;

public interface LectorEvidencias {
    boolean tienePerfilActivo(String idUsuario);
    boolean esPropietario(String idUsuario, String idDespacho);
    /** La foto de un recojo solo la lee quien tiene ese recojo, aunque otro repartidor entregara el despacho. */
    default boolean esDeRecojo(String idEvidencia) { return false; }
    default boolean esPropietarioRecojo(String idUsuario, String idEvidencia) { return false; }
    Optional<Referencia> consultar(String idEvidencia);
    int vigenciaSegundos();
    record Referencia(String idEvidencia, String idDespacho, String rutaObjeto, String estado) {}
}
