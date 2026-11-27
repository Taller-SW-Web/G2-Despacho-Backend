package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LiberacionesPendientes;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LiberadorCapacidad;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.application.PublicadorInterno;
import java.time.Clock;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Envía la liberación confirmada a Operación por RabbitMQ, sin esperar una respuesta HTTP. */
public final class LiberadorCapacidadRabbit implements LiberadorCapacidad {
    private final PublicadorInterno publicador;
    private final JsonMapper json;
    private final Clock reloj;

    public LiberadorCapacidadRabbit(PublicadorInterno publicador, JsonMapper json, Clock reloj) {
        this.publicador = publicador;
        this.json = json;
        this.reloj = reloj;
    }

    @Override
    public Resultado liberar(LiberacionesPendientes.Envio envio) {
        var datos =
                json.readTree(
                        json.writeValueAsString(
                                Map.of(
                                        "idReserva", envio.reserva(),
                                        "idDespacho", envio.despacho(),
                                        "motivo", "RECEPCION_EN_CENTRO")));
        try {
            var ahora = reloj.instant();
            publicador.publicar(envio.id(), "LIBERACION_CAPACIDAD", datos, ahora);
            return new Resultado(true, false, null, ahora);
        } catch (RuntimeException error) {
            return Resultado.fallo(false, "RABBITMQ_NO_DISPONIBLE");
        }
    }
}
