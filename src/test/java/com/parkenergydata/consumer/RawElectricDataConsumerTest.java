package com.parkenergydata.consumer;

import com.parkenergydata.common.BusinessException;
import com.parkenergydata.config.ParkRabbitProperties;
import com.parkenergydata.mq.AlarmReceiptPublisher;
import com.parkenergydata.mq.ConfirmedMessagePublisher;
import com.parkenergydata.parser.PayloadParser;
import com.parkenergydata.service.DataIngestService;
import com.parkenergydata.service.GatewayAlarmIngestService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RawElectricDataConsumerTest {
    final PayloadParser parser=mock(PayloadParser.class);
    final ConfirmedMessagePublisher publisher=mock(ConfirmedMessagePublisher.class);
    final Channel channel=mock(Channel.class);
    final RawElectricDataConsumer consumer=new RawElectricDataConsumer(parser,mock(DataIngestService.class),
            publisher,new ParkRabbitProperties("test.exchange","test.queue","raw","alarm.queue","alarm"),
            mock(GatewayAlarmIngestService.class),mock(AlarmReceiptPublisher.class));
    Message message() {
        var p=new MessageProperties();
        p.setDeliveryTag(10);p.setConsumerQueue("test.queue");
        return new Message("sample".getBytes(java.nio.charset.StandardCharsets.UTF_8),p);
    }
    @Test void retainsOriginalWhenRetryPublishCannotBeConfirmed() throws Exception {
        when(parser.parseForwardMessage("sample")).thenThrow(new IllegalStateException("database offline"));
        doThrow(new IllegalStateException("broker offline")).when(publisher).send(anyString(),anyString(),any(Message.class));
        consumer.consume(message(),channel);
        verify(channel).basicNack(10,false,true);
        verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void ackFollowsConfirmedRetryRoute() throws Exception {
        when(parser.parseForwardMessage("sample")).thenThrow(new IllegalStateException("database offline"));
        consumer.consume(message(),channel);
        var order=inOrder(publisher,channel);
        order.verify(publisher).send(eq("test.exchange"),eq("raw.retry"),any(Message.class));
        order.verify(channel).basicAck(10,false);
    }
    @Test void malformedPayloadIsRetainedInDeadLetterQueue() throws Exception {
        when(parser.parseForwardMessage("sample")).thenThrow(new BusinessException("invalid JSON"));
        consumer.consume(message(),channel);
        verify(publisher).send(eq("test.exchange"),eq("raw.dlq"),any(Message.class));
        verify(channel).basicAck(10,false);
    }
}
