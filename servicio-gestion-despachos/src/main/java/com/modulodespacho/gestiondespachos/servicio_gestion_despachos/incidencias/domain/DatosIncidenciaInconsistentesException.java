package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain;

/** Distingue datos canónicos incoherentes de una falla de acceso a la fuente. */
public final class DatosIncidenciaInconsistentesException extends RuntimeException {

    public DatosIncidenciaInconsistentesException(String mensaje) {
        super(mensaje);
    }

    public DatosIncidenciaInconsistentesException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
