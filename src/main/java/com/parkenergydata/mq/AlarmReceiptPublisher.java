package com.parkenergydata.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergydata.config.ParkRabbitProperties;
import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.GatewayAlarmPayload;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AlarmReceiptPublisher {
    private final RabbitTemplate rabbitTemplate;
    private final ParkRabbitProperties properties;
    private final ObjectMapper objectMapper;

    public AlarmReceiptPublisher(RabbitTemplate rabbitTemplate, ParkRabbitProperties properties, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void publish(AccessForwardMessage forward, GatewayAlarmPayload alarm, String status, String error) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("gatewayId", forward.gatewayId());
            result.put("messageId", forward.messageId());
            result.put("eventId", alarm.eventId());
            result.put("status", status);
            result.put("error", error);
            result.put("processedAt", System.currentTimeMillis());
            rabbitTemplate.convertAndSend(properties.exchange(), properties.resultRoutingKey(),
                    objectMapper.writeValueAsString(result));
        } catch (Exception exception) {
            throw new IllegalStateException("publish alarm receipt failed", exception);
        }
    }
}
