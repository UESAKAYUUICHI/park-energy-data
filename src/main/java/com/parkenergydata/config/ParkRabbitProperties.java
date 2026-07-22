package com.parkenergydata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "park.rabbitmq")
public record ParkRabbitProperties(String exchange, String rawDataQueue, String rawDataRoutingKey) {
}
