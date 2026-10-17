package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain;

import java.util.ArrayList;
import java.util.List;

/** Política de lectura de ES-F04-02; los comandos deben volver a validar sus precondiciones. */
public record AccionesIncidencia(Accion confirmarRecepcion, Accion reprogramar, Accion cerrarComoDevueltoAOrigen) {
    public record Accion(boolean habilitada, List<String> motivosBloqueo) {
        public Accion { motivosBloqueo = List.copyOf(motivosBloqueo); }
    }

    public static AccionesIncidencia calcular(String estado, boolean recibido, boolean anulado, int intentos, int maximo) {
        if (intentos < 0 || maximo < 1) throw new DatosIncidenciaInconsistentesException("Política de intentos inválida");
        if (!"FALLIDO".equals(estado)) {
            Accion historica = accion(List.of("ESTADO_NO_FALLIDO"));
            return new AccionesIncidencia(historica, historica, historica);
        }
        List<String> bloqueos = new ArrayList<>();
        if (!recibido) bloqueos.add("RECEPCION_PENDIENTE");
        if (anulado) bloqueos.add("PEDIDO_ANULADO");
        if (intentos >= maximo) bloqueos.add("MAXIMO_INTENTOS_ALCANZADO");
        return new AccionesIncidencia(accion(recibido ? List.of("RECEPCION_YA_CONFIRMADA") : List.of()),
                accion(bloqueos), accion(recibido ? List.of() : List.of("RECEPCION_PENDIENTE")));
    }

    private static Accion accion(List<String> motivos) { return new Accion(motivos.isEmpty(), motivos); }
}
