package com.parkenergydata.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

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
    Queue rawElectricDataRetryQueue(ParkRabbitProperties properties) {
        return QueueBuilder.durable(properties.retryQueue())
                .withArgument("x-message-ttl", properties.retryDelayMilliseconds())
                .withArgument("x-dead-letter-exchange", properties.exchange())
                .withArgument("x-dead-letter-routing-key", properties.rawDataRoutingKey())
                .build();
    }

    @Bean
    Queue rawElectricDataDeadLetterQueue(ParkRabbitProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue()).build();
    }

    @Bean
    Queue gatewayAlarmQueue(ParkRabbitProperties properties) {
        return new Queue(properties.alarmQueue(), true);
    }

    @Bean
    Queue gatewayAlarmRetryQueue(ParkRabbitProperties properties) {
        return QueueBuilder.durable(properties.alarmRetryQueue())
                .withArgument("x-message-ttl", properties.retryDelayMilliseconds())
                .withArgument("x-dead-letter-exchange", properties.exchange())
                .withArgument("x-dead-letter-routing-key", properties.alarmRoutingKey())
                .build();
    }

    @Bean
    Queue gatewayAlarmDeadLetterQueue(ParkRabbitProperties properties) {
        return QueueBuilder.durable(properties.alarmDeadLetterQueue()).build();
    }

    @Bean
    Binding rawElectricDataBinding(@Qualifier("rawElectricDataQueue") Queue rawElectricDataQueue, DirectExchange parkEnergyExchange,
                                   ParkRabbitProperties properties) {
        return BindingBuilder.bind(rawElectricDataQueue)
                .to(parkEnergyExchange)
                .with(properties.rawDataRoutingKey());
    }

    @Bean
    Binding rawElectricDataRetryBinding(DirectExchange parkEnergyExchange, @Qualifier("rawElectricDataRetryQueue") Queue rawElectricDataRetryQueue,
                                        ParkRabbitProperties properties) {
        return BindingBuilder.bind(rawElectricDataRetryQueue).to(parkEnergyExchange).with(properties.retryRoutingKey());
    }

    @Bean
    Binding rawElectricDataDeadLetterBinding(DirectExchange parkEnergyExchange, @Qualifier("rawElectricDataDeadLetterQueue") Queue rawElectricDataDeadLetterQueue,
                                             ParkRabbitProperties properties) {
        return BindingBuilder.bind(rawElectricDataDeadLetterQueue).to(parkEnergyExchange).with(properties.deadLetterRoutingKey());
    }

    @Bean
    Binding gatewayAlarmBinding(DirectExchange parkEnergyExchange,
                                @Qualifier("gatewayAlarmQueue") Queue gatewayAlarmQueue,
                                ParkRabbitProperties properties) {
        return BindingBuilder.bind(gatewayAlarmQueue).to(parkEnergyExchange).with(properties.alarmRoutingKey());
    }

    @Bean
    Binding gatewayAlarmRetryBinding(DirectExchange parkEnergyExchange,
                                     @Qualifier("gatewayAlarmRetryQueue") Queue gatewayAlarmRetryQueue,
                                     ParkRabbitProperties properties) {
        return BindingBuilder.bind(gatewayAlarmRetryQueue).to(parkEnergyExchange).with(properties.alarmRetryRoutingKey());
    }

    @Bean
    Binding gatewayAlarmDeadLetterBinding(DirectExchange parkEnergyExchange,
                                          @Qualifier("gatewayAlarmDeadLetterQueue") Queue gatewayAlarmDeadLetterQueue,
                                          ParkRabbitProperties properties) {
        return BindingBuilder.bind(gatewayAlarmDeadLetterQueue).to(parkEnergyExchange).with(properties.alarmDeadLetterRoutingKey());
    }
}
