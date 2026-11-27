package com.modulodespacho.operacionreparto.servicio_operacion_reparto.mensajeria.infrastructure;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.mensajeria.application.ProcesadorMensajesInternos;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.mensajeria.domain.MensajeInterno;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("!integracion-local")
@ConditionalOnProperty(name = "despacho.rabbitmq.habilitado", havingValue = "true")
public class ConsumidorLiberacionesCapacidad {
    private final ProcesadorMensajesInternos procesador;
    private final JsonMapper json;

    public ConsumidorLiberacionesCapacidad(ProcesadorMensajesInternos procesador, JsonMapper json) {
        this.procesador = procesador;
        this.json = json;
    }

    @RabbitListener(queues = "operacion.liberaciones", ackMode = "MANUAL")
    public void consumir(Message mensaje, Channel canal) throws IOException {
        long entrega = mensaje.getMessageProperties().getDeliveryTag();
        try {
            procesador.procesar(json.readValue(mensaje.getBody(), MensajeInterno.class));
            canal.basicAck(entrega, false);
        } catch (Exception error) {
            canal.basicNack(entrega, false, false);
        }
    }
}
