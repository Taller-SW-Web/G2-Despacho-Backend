package com.modulodespacho.operacionreparto.servicio_operacion_reparto.mensajeria.infrastructure;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Topología de consumo: cada cola tiene su propia cola de espera en el DLX. */
@Configuration(proxyBeanMethods = false)
@Profile("!integracion-local")
@ConditionalOnProperty(name = "despacho.rabbitmq.habilitado", havingValue = "true")
public class ConfiguracionRabbitMQ {
    static final String DLX = "despacho.dlx";

    @Bean
    TopicExchange exchangeCapacidad() {
        return new TopicExchange("despacho.capacidad");
    }

    @Bean
    TopicExchange exchangeEstado() {
        return new TopicExchange("despacho.estado");
    }

    @Bean
    DirectExchange exchangeErrores() {
        return new DirectExchange(DLX);
    }

    @Bean
    Queue colaLiberaciones() {
        return QueueBuilder.durable("operacion.liberaciones")
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", "operacion.liberaciones.dlq")
                .build();
    }

    @Bean
    Queue colaLiberacionesErrores() {
        return QueueBuilder.durable("operacion.liberaciones.dlq").build();
    }

    @Bean
    Queue colaEstado() {
        return QueueBuilder.durable("despacho.eventos.estado")
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", "despacho.eventos.estado.dlq")
                .build();
    }

    @Bean
    Queue colaEstadoErrores() {
        return QueueBuilder.durable("despacho.eventos.estado.dlq").build();
    }

    @Bean
    Binding enlaceLiberaciones(Queue colaLiberaciones, TopicExchange exchangeCapacidad) {
        return BindingBuilder.bind(colaLiberaciones)
                .to(exchangeCapacidad)
                .with("operacion.liberacion.*");
    }

    @Bean
    Binding enlaceEstado(Queue colaEstado, TopicExchange exchangeEstado) {
        return BindingBuilder.bind(colaEstado).to(exchangeEstado).with("despacho.proyeccion");
    }

    @Bean
    Binding enlaceErroresLiberaciones(
            Queue colaLiberacionesErrores, DirectExchange exchangeErrores) {
        return BindingBuilder.bind(colaLiberacionesErrores)
                .to(exchangeErrores)
                .with("operacion.liberaciones.dlq");
    }

    @Bean
    Binding enlaceErroresEstado(Queue colaEstadoErrores, DirectExchange exchangeErrores) {
        return BindingBuilder.bind(colaEstadoErrores)
                .to(exchangeErrores)
                .with("despacho.eventos.estado.dlq");
    }
}
