package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.RabbitmqTestcontainersConfiguration;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.TestcontainersConfiguration;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LiberacionesPendientes;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LiberadorCapacidad;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure.LiberadorCapacidadRabbit;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.application.PublicadorInterno;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.domain.MensajeInterno;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure.EntregaEventosProgramacion;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL y RabbitMQ desechables: el outbox publica y se marca enviado solo con confirmación. */
@Import({
    TestcontainersConfiguration.class,
    RabbitmqTestcontainersConfiguration.class,
    PublicacionInternaRabbitmqTests.ColasPrueba.class
})
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "despacho.rabbitmq.habilitado=true",
            "despacho.demo.sin-autenticacion=true",
            "despacho.programacion.recuperacion-habilitada=false",
            "despacho.recepcion.procesador-habilitado=false",
            "despacho.seguridad.url=http://127.0.0.1:1",
            "despacho.productos.url=http://127.0.0.1:1",
            "despacho.operacion.url=http://127.0.0.1:1",
            "despacho.ventas.url=http://127.0.0.1:1"
        })
class PublicacionInternaRabbitmqTests {
    @Autowired JdbcClient sql;
    @Autowired JsonMapper json;
    @Autowired EntregaEventosProgramacion eventos;
    @Autowired RabbitTemplate plantilla;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ColasPrueba {
        @org.springframework.context.annotation.Bean
        org.springframework.amqp.core.Queue colaLiberaciones() {
            return new org.springframework.amqp.core.Queue("operacion.liberaciones");
        }

        @org.springframework.context.annotation.Bean
        org.springframework.amqp.core.Queue colaEstado() {
            return new org.springframework.amqp.core.Queue("despacho.eventos.estado");
        }

        @org.springframework.context.annotation.Bean
        org.springframework.amqp.core.Binding enlaceLiberaciones(
                org.springframework.amqp.core.Queue colaLiberaciones,
                org.springframework.amqp.core.TopicExchange exchangeCapacidad) {
            return org.springframework.amqp.core.BindingBuilder.bind(colaLiberaciones)
                    .to(exchangeCapacidad)
                    .with("operacion.liberacion.*");
        }

        @org.springframework.context.annotation.Bean
        org.springframework.amqp.core.Binding enlaceEstado(
                org.springframework.amqp.core.Queue colaEstado,
                org.springframework.amqp.core.TopicExchange exchangeEstado) {
            return org.springframework.amqp.core.BindingBuilder.bind(colaEstado)
                    .to(exchangeEstado)
                    .with("despacho.proyeccion");
        }
    }

    @BeforeEach
    void vaciarColas() {
        while (plantilla.receive("operacion.liberaciones", 100) != null) {}
        while (plantilla.receive("despacho.eventos.estado", 100) != null) {}
    }

    @Test
    void publicaLiberacionDelOutboxYMarcaElEventoEnviado() {
        UUID despacho = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        UUID evento = UUID.randomUUID();
        sql.sql(
                        """
                INSERT INTO despachos(id, pedido_id, estado, version, codigo_rastreo_interno, numero_intentos)
                VALUES (:id, :pedido, 'ASIGNADO', 1, :codigo, 0)
                """)
                .param("id", despacho)
                .param("pedido", "PED-RABBIT-" + despacho)
                .param("codigo", "TRK-RABBIT-" + despacho)
                .update();
        sql.sql(
                        """
                INSERT INTO eventos_salientes(id, despacho_id, destino, tipo_evento, payload)
                VALUES (:id, :despacho, 'OPERACION_LIBERACION', 'LIBERACION_CAPACIDAD', CAST(:payload AS jsonb))
                """)
                .param("id", evento)
                .param("despacho", despacho)
                .param(
                        "payload",
                        json.writeValueAsString(
                                Map.of(
                                        "idReserva", reserva.toString(),
                                        "idDespacho", despacho.toString(),
                                        "motivo", "ENTREGA")))
                .update();

        eventos.ejecutar();

        Message recibido = plantilla.receive("operacion.liberaciones", 5000);
        assertNotNull(recibido, "El evento interno debió publicarse en RabbitMQ");
        var sobre = json.readValue(recibido.getBody(), MensajeInterno.class);
        assertEquals(evento, sobre.eventId());
        assertEquals(evento, sobre.claveIdempotencia());
        assertEquals("LIBERACION_CAPACIDAD", sobre.tipo());
        assertEquals(1, sobre.version());
        assertEquals(
                "operacion.liberacion." + reserva,
                recibido.getMessageProperties().getReceivedRoutingKey());
        assertEquals(
                "ENVIADO",
                sql.sql("SELECT estado_envio FROM eventos_salientes WHERE id = :id")
                        .param("id", evento)
                        .query(String.class)
                        .single());
    }

    @Test
    void laLiberacionPorRabbitSeConsideraConfirmada() {
        var publicador = new PublicadorCaptura();
        UUID id = UUID.randomUUID();
        UUID despacho = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        var envio =
                new LiberacionesPendientes.Envio(
                        id,
                        UUID.randomUUID(),
                        despacho.toString(),
                        null,
                        reserva.toString(),
                        null,
                        1,
                        false,
                        true);

        LiberadorCapacidad.Resultado resultado =
                new LiberadorCapacidadRabbit(publicador, json, Clock.systemUTC()).liberar(envio);

        assertTrue(resultado.confirmada());
        assertFalse(resultado.requiereRevision());
        assertNotNull(resultado.liberadaEn());
        assertEquals("LIBERACION_CAPACIDAD", publicador.ultimo.tipo());
        assertEquals("RECEPCION_EN_CENTRO", publicador.ultimo.datos().path("motivo").asText());
        assertEquals(reserva.toString(), publicador.ultimo.datos().path("idReserva").asText());
    }

    private static final class PublicadorCaptura implements PublicadorInterno {
        private MensajeInterno ultimo;

        @Override
        public void publicar(
                UUID eventId,
                String tipo,
                tools.jackson.databind.JsonNode datos,
                java.time.Instant ocurridoEn) {
            ultimo = new MensajeInterno(eventId, tipo, 1, eventId, ocurridoEn, datos);
        }
    }
}
