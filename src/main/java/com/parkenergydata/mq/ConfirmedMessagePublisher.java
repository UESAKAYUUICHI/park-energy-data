package com.parkenergydata.mq;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ConfirmedMessagePublisher {
    private final RabbitTemplate rabbit;
    public ConfirmedMessagePublisher(RabbitTemplate rabbit) { this.rabbit=rabbit; }
    public void send(String exchange,String routingKey,Message message) {
        CorrelationData correlation=new CorrelationData(UUID.randomUUID().toString());
        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        try {
            rabbit.send(exchange,routingKey,message,correlation);
            CorrelationData.Confirm confirm=correlation.getFuture().get(10,TimeUnit.SECONDS);
            if(!confirm.isAck() || correlation.getReturned()!=null)
                throw new IllegalStateException("Message not durably routed");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Message confirmation interrupted",e);
        } catch(Exception e) { throw new IllegalStateException("Message confirmation failed",e); }
    }
}
