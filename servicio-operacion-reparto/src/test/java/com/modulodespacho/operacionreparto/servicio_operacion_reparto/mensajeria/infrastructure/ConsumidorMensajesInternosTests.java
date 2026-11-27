package com.modulodespacho.operacionreparto.servicio_operacion_reparto.mensajeria.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.RabbitmqTestcontainersConfiguration;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.TestcontainersConfiguration;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.mensajeria.domain.MensajeInterno;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** RabbitMQ y PostgreSQL desechables: idempotencia por eventId y envío a DLQ. */
@Import({TestcontainersConfiguration.class, RabbitmqTestcontainersConfiguration.class})
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "despacho.rabbitmq.habilitado=true",
            "despacho.demo.sin-autenticacion=true",
            "SEGURIDAD_URL=http://127.0.0.1:1",
            "despacho.seguridad.url=http://127.0.0.1:1",
            "despacho.gestion.url=http://127.0.0.1:1"
        })
class ConsumidorMensajesInternosTests {
    @MockitoBean GestorJornadas gestor;
    @Autowired RabbitTemplate plantilla;
    @Autowired JsonMapper json;

    @BeforeEach
    void vaciarColas() {
        while (plantilla.receive("operacion.liberaciones", 100) != null) {}
        while (plantilla.receive("despacho.eventos.estado", 100) != null) {}
        while (plantilla.receive("operacion.liberaciones.dlq", 100) != null) {}
        while (plantilla.receive("despacho.eventos.estado.dlq", 100) != null) {}
    }

    @Test
    void unaReentregaConElMismoEventIdNoRepiteLaLiberacion() {
        UUID id = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        UUID despacho = UUID.randomUUID();
        var sobre =
                new MensajeInterno(
                        id,
                        "LIBERACION_CAPACIDAD",
                        1,
                        id,
                        Instant.now(),
                        json.readTree(
                                json.writeValueAsString(
                                        java.util.Map.of(
                                                "idReserva", reserva.toString(),
                                                "idDespacho", despacho.toString(),
                                                "motivo", "RECEPCION_EN_CENTRO"))));

        publicar("despacho.capacidad", "operacion.liberacion." + reserva, sobre);
        publicar("despacho.capacidad", "operacion.liberacion." + reserva, sobre);

        verify(gestor, timeout(10000).times(1))
                .liberar(reserva, despacho, "RECEPCION_EN_CENTRO", "modulo-despacho");
        verify(gestor, after(1000).times(1))
                .liberar(reserva, despacho, "RECEPCION_EN_CENTRO", "modulo-despacho");
        assertNull(plantilla.receive("operacion.liberaciones.dlq", 100));
    }

    @Test
    void unTipoDesconocidoTerminaEnLaColaDeEspera() {
        UUID id = UUID.randomUUID();
        var sobre =
                new MensajeInterno(
                        id, "TIPO_DESCONOCIDO", 1, id, Instant.now(), json.readTree("{}"));
        publicar("despacho.capacidad", "operacion.liberacion." + UUID.randomUUID(), sobre);

        Message fallido = plantilla.receive("operacion.liberaciones.dlq", 10000);
        assertNotNull(fallido, "El mensaje no procesable debió terminar en la DLQ");
        assertEquals(
                id.toString(),
                json.readValue(fallido.getBody(), MensajeInterno.class).eventId().toString());
        verifyNoInteractions(gestor);
    }

    private void publicar(String exchange, String routingKey, MensajeInterno sobre) {
        plantilla.convertAndSend(exchange, routingKey, json.writeValueAsString(sobre));
    }
}
