package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application;

import java.util.Optional;

public interface LectorEvidencias {
    boolean tienePerfilActivo(String idUsuario);
    boolean esPropietario(String idUsuario, String idDespacho);
    Optional<Referencia> consultar(String idEvidencia);
    int vigenciaSegundos();
    record Referencia(String idEvidencia, String idDespacho, String rutaObjeto, String estado) {}
}
