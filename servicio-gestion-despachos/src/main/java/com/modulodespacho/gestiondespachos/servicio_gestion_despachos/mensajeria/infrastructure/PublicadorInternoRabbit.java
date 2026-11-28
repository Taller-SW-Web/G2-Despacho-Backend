package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.infrastructure;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.application.PublicadorInterno;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.domain.MensajeInterno;
import com.rabbitmq.client.AMQP;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Publica con confirmación sincrónica del broker antes de marcar el evento como enviado. */
public final class PublicadorInternoRabbit implements PublicadorInterno {
    private static final int VERSION = 1;
    private static final long ESPERA_CONFIRMACION_MS = 5000L;

    private final RabbitTemplate plantilla;
    private final JsonMapper json;

    public PublicadorInternoRabbit(RabbitTemplate plantilla, JsonMapper json) {
        this.plantilla = plantilla;
        this.json = json;
    }

    @Override
    public void publicar(UUID eventId, String tipo, JsonNode datos, Instant ocurridoEn) {
        boolean proyeccion = "PROYECCION_DESPACHO".equals(tipo);
        String exchange = proyeccion ? "despacho.estado" : "despacho.capacidad";
        String routingKey =
                proyeccion
                        ? "despacho.proyeccion"
                        : "operacion.liberacion." + datos.path("idReserva").asText();
        var mensaje =
                new MensajeInterno(
                        eventId,
                        tipo,
                        VERSION,
                        eventId,
                        ocurridoEn == null ? Instant.now() : ocurridoEn,
                        datos);
        byte[] cuerpo = json.writeValueAsBytes(mensaje);
        plantilla.execute(
                canal -> {
                    canal.basicPublish(
                            exchange,
                            routingKey,
                            new AMQP.BasicProperties.Builder()
                                    .contentType("application/json")
                                    .deliveryMode(2)
                                    .messageId(eventId.toString())
                                    .build(),
                            cuerpo);
                    try {
                        canal.waitForConfirmsOrDie(ESPERA_CONFIRMACION_MS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Se interrumpió la espera de confirmación de RabbitMQ", error);
                    } catch (Exception error) {
                        throw new IllegalStateException(
                                "RabbitMQ no confirmó la publicación del evento interno", error);
                    }
                    return null;
                });
    }
}
