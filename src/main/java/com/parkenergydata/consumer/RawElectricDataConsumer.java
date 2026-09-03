package com.parkenergydata.consumer;

import java.nio.charset.StandardCharsets;

import com.parkenergydata.common.BusinessException;
import com.parkenergydata.config.ParkRabbitProperties;
import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.GatewayUploadPayload;
import com.parkenergydata.dto.GatewayAlarmPayload;
import com.parkenergydata.mq.AlarmReceiptPublisher;
import com.parkenergydata.parser.PayloadParser;
import com.parkenergydata.service.DataIngestService;
import com.parkenergydata.service.GatewayAlarmIngestService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RawElectricDataConsumer {
    private static final Logger log = LoggerFactory.getLogger(RawElectricDataConsumer.class);

    private final PayloadParser payloadParser;
    private final DataIngestService dataIngestService;
    private final RabbitTemplate rabbitTemplate;
    private final ParkRabbitProperties rabbitProperties;
    private final GatewayAlarmIngestService gatewayAlarmIngestService;
    private final AlarmReceiptPublisher alarmReceiptPublisher;

    public RawElectricDataConsumer(PayloadParser payloadParser, DataIngestService dataIngestService,
                                   RabbitTemplate rabbitTemplate, ParkRabbitProperties rabbitProperties,
                                   GatewayAlarmIngestService gatewayAlarmIngestService,
                                   AlarmReceiptPublisher alarmReceiptPublisher) {
        this.payloadParser = payloadParser;
        this.dataIngestService = dataIngestService;
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitProperties = rabbitProperties;
        this.gatewayAlarmIngestService = gatewayAlarmIngestService;
        this.alarmReceiptPublisher = alarmReceiptPublisher;
    }

    @RabbitListener(queues = {"${park.rabbitmq.raw-data-queue}", "${park.rabbitmq.alarm-queue}"},
            autoStartup = "${park.data.consumer-enabled:true}")
    public void consume(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            AccessForwardMessage forward = payloadParser.parseForwardMessage(body);
            if ("ALARM_UPLOAD".equals(forward.payloadType())) {
                GatewayAlarmPayload alarm = payloadParser.parseGatewayAlarmPayload(forward.rawPayload());
                gatewayAlarmIngestService.ingest(forward, alarm);
                alarmReceiptPublisher.publish(forward, alarm, "DATA_CONSUMED", null);
            } else {
                GatewayUploadPayload payload = payloadParser.parseGatewayPayload(forward.rawPayload());
                dataIngestService.ingest(forward, payload);
            }
            channel.basicAck(deliveryTag, false);
        } catch (BusinessException | IllegalArgumentException ex) {
            log.warn("Discard invalid raw electric data message: {}", ex.getMessage());
            try {
                AccessForwardMessage forward = payloadParser.parseForwardMessage(body);
                if ("ALARM_UPLOAD".equals(forward.payloadType())) {
                    GatewayAlarmPayload alarm = payloadParser.parseGatewayAlarmPayload(forward.rawPayload());
                    alarmReceiptPublisher.publish(forward, alarm, "DATA_FAILED", ex.getMessage());
                } else {
                    dataIngestService.recordInvalid(forward, ex.getMessage());
                }
            } catch (Exception recordException) {
                log.error("Unable to persist invalid ingest event", recordException);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            int retryCount = retryCount(message) + 1;
            boolean alarmQueue = rabbitProperties.alarmQueue()
                    .equals(message.getMessageProperties().getConsumerQueue());
            if (retryCount > rabbitProperties.maxRetryAttempts()) {
                route(message, alarmQueue ? rabbitProperties.alarmDeadLetterRoutingKey()
                        : rabbitProperties.deadLetterRoutingKey(), retryCount, ex.getMessage());
                recordDeadLetter(body, ex.getMessage());
                log.error("Raw electric data moved to dead-letter queue after {} retries", retryCount - 1, ex);
            } else {
                route(message, alarmQueue ? rabbitProperties.alarmRetryRoutingKey()
                        : rabbitProperties.retryRoutingKey(), retryCount, ex.getMessage());
                log.warn("Recoverable raw electric data failure; scheduled retry {}/{}: {}", retryCount,
                        rabbitProperties.maxRetryAttempts(), ex.getMessage());
            }
            channel.basicAck(deliveryTag, false);
        }
    }

    private void route(Message original, String routingKey, int retryCount, String reason) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(original.getMessageProperties().getContentType());
        properties.setContentEncoding(original.getMessageProperties().getContentEncoding());
        properties.setHeader("x-park-retry-count", retryCount);
        properties.setHeader("x-park-last-error", shortReason(reason));
        rabbitTemplate.send(rabbitProperties.exchange(), routingKey, new Message(original.getBody(), properties));
    }

    private int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-park-retry-count");
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String shortReason(String reason) {
        if (reason == null || reason.isBlank()) return "unknown error";
        return reason.substring(0, Math.min(reason.length(), 500));
    }

    private void recordDeadLetter(String body, String reason) {
        try {
            dataIngestService.recordDeadLetter(payloadParser.parseForwardMessage(body), reason);
        } catch (Exception eventException) {
            log.error("Unable to persist dead-letter ingest event", eventException);
        }
    }
}
