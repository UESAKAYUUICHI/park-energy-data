package com.parkenergydata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "park.rabbitmq")
public record ParkRabbitProperties(String exchange, String rawDataQueue, String rawDataRoutingKey,
                                   String alarmQueue, String alarmRoutingKey) {
    public String retryQueue() { return rawDataQueue + ".retry"; }
    public String retryRoutingKey() { return rawDataRoutingKey + ".retry"; }
    public String deadLetterQueue() { return rawDataQueue + ".dlq"; }
    public String deadLetterRoutingKey() { return rawDataRoutingKey + ".dlq"; }
    public int maxRetryAttempts() { return 3; }
    public int retryDelayMilliseconds() { return 30_000; }
    public String resultRoutingKey() { return rawDataRoutingKey + ".result"; }
    public String alarmRetryQueue() { return alarmQueue + ".retry"; }
    public String alarmRetryRoutingKey() { return alarmRoutingKey + ".retry"; }
    public String alarmDeadLetterQueue() { return alarmQueue + ".dlq"; }
    public String alarmDeadLetterRoutingKey() { return alarmRoutingKey + ".dlq"; }
}
