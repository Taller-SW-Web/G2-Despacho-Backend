package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application;

import java.util.Optional;

/** Comprueba en el bucket privado que la fotografía autorizada realmente se cargó (ES-F03-05 RN-03). */
public interface VerificadorCargaStorage {
    /** Vacío si el objeto no existe; una indisponibilidad lanza EvidenciaNoDisponible. */
    Optional<ObjetoCargado> verificar(String rutaObjeto);

    record ObjetoCargado(long bytes, String contentType) {}
}
