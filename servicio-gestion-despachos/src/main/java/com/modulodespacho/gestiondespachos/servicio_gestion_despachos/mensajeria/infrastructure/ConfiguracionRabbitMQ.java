package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.infrastructure;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.mensajeria.application.PublicadorInterno;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/** Declaración de los exchanges de salida; las colas pertenecen a Operación. */
@Configuration(proxyBeanMethods = false)
@Profile("!integracion-local")
@ConditionalOnProperty(name = "despacho.rabbitmq.habilitado", havingValue = "true")
public class ConfiguracionRabbitMQ {
    @Bean
    TopicExchange exchangeCapacidad() {
        return new TopicExchange("despacho.capacidad");
    }

    @Bean
    TopicExchange exchangeEstado() {
        return new TopicExchange("despacho.estado");
    }

    @Bean
    PublicadorInterno publicadorInterno(RabbitTemplate plantilla, JsonMapper json) {
        return new PublicadorInternoRabbit(plantilla, json);
    }
}
