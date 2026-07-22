package com.parkenergydata.consumer;

import java.nio.charset.StandardCharsets;

import com.parkenergydata.common.BusinessException;
import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.GatewayUploadPayload;
import com.parkenergydata.parser.PayloadParser;
import com.parkenergydata.service.DataIngestService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RawElectricDataConsumer {
    private static final Logger log = LoggerFactory.getLogger(RawElectricDataConsumer.class);

    private final PayloadParser payloadParser;
    private final DataIngestService dataIngestService;

    public RawElectricDataConsumer(PayloadParser payloadParser, DataIngestService dataIngestService) {
        this.payloadParser = payloadParser;
        this.dataIngestService = dataIngestService;
    }

    @RabbitListener(queues = "${park.rabbitmq.raw-data-queue}", autoStartup = "${park.data.consumer-enabled:true}")
    public void consume(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            AccessForwardMessage forward = payloadParser.parseForwardMessage(body);
            GatewayUploadPayload payload = payloadParser.parseGatewayPayload(forward.rawPayload());
            dataIngestService.ingest(forward, payload);
            channel.basicAck(deliveryTag, false);
        } catch (BusinessException | IllegalArgumentException ex) {
            log.warn("Discard invalid raw electric data message: {}", ex.getMessage());
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            log.error("Recoverable raw electric data consume failed, message will be requeued", ex);
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
