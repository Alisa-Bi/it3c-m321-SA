package ch.m321.chatapp.chat.messaging;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.stereotype.Component;

/**
 * Schickt fertige Nachrichten (mit bereits gesetzter id und sentAt) an
 * RabbitMQ, direkt an die Queue chat.persist (Standard-Exchange,
 * Routing-Key = Queue-Name, siehe RabbitMqConfig).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MessageProducer {

    private final AmqpTemplate amqpTemplate;

    public void publish(NewMessageRequest message) {
        log.info("Sende Nachricht {} fuer Chatraum {} an {}", message.id(), message.roomId(), RabbitMqConfig.PERSIST_QUEUE);
        amqpTemplate.convertAndSend(RabbitMqConfig.PERSIST_QUEUE, message);
    }
}
