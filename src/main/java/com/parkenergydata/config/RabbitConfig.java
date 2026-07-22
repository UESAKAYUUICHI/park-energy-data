package com.parkenergydata.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    @Bean
    DirectExchange parkEnergyExchange(ParkRabbitProperties properties) {
        return new DirectExchange(properties.exchange(), true, false);
    }

    @Bean
    Queue rawElectricDataQueue(ParkRabbitProperties properties) {
        return new Queue(properties.rawDataQueue(), true);
    }

    @Bean
    Binding rawElectricDataBinding(Queue rawElectricDataQueue, DirectExchange parkEnergyExchange,
                                   ParkRabbitProperties properties) {
        return BindingBuilder.bind(rawElectricDataQueue)
                .to(parkEnergyExchange)
                .with(properties.rawDataRoutingKey());
    }
}
