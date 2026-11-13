package ch.m321.chatapp.chat.messaging;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.stereotype.Component;

/**
 * Schickt fertige Nachrichten (mit bereits gesetzter id und sentAt) an
 * RabbitMQ, und zwar auf zwei Wegen: zuerst an die Queue chat.persist
 * (Standard-Exchange, Routing-Key = Queue-Name) fuer die Speicherung, danach
 * an den Fanout-Exchange chat.delivery fuer die Live-Zustellung.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MessageProducer {

    private final AmqpTemplate amqpTemplate;

    // Veroeffentlicht dieselbe Nachricht zuerst fuer die Speicherung und dann
    // fuer die Live-Zustellung. Die Reihenfolge ist Absicht: Eine Nachricht
    // soll nicht live erscheinen, bevor sie fuer die Speicherung eingereiht
    // ist (siehe docs/spec-live-zustellung.md, Abschnitt 4.1). Die beiden
    // Aufrufe sind nicht atomar; schlaegt der zweite fehl, bleibt der erste
    // bestehen.
    public void publish(NewMessageRequest message) {
        log.info("Publishing message {} for room {} to {}", message.id(), message.roomId(), RabbitMqConfig.PERSIST_QUEUE);
        amqpTemplate.convertAndSend(RabbitMqConfig.PERSIST_QUEUE, message);

        log.info("Publishing message {} for room {} to {}", message.id(), message.roomId(), RabbitMqConfig.DELIVERY_EXCHANGE);
        amqpTemplate.convertAndSend(RabbitMqConfig.DELIVERY_EXCHANGE, "", message);
    }
}
