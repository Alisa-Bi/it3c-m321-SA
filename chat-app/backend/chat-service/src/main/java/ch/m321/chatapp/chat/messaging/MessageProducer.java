package ch.m321.chatapp.chat.messaging;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Schickt neue Nachrichten an RabbitMQ, damit sie asynchron gespeichert
 * und (spaeter) an alle Empfaenger verteilt werden koennen. Wird von
 * MessageService.publishNewMessage aufgerufen.
 */
@Component
@Slf4j
public class MessageProducer {

    private final AmqpTemplate amqpTemplate;

    public MessageProducer(
            @Qualifier("chatRabbitTemplate") AmqpTemplate amqpTemplate) {
        this.amqpTemplate = amqpTemplate;
    }

    public void publish(NewMessageRequest request) {
        log.info("Sende neue Nachricht fuer Chatraum {} an RabbitMQ", request.roomId());

        amqpTemplate.convertAndSend(
                RabbitMqConfig.CHAT_EXCHANGE,
                RabbitMqConfig.MESSAGE_ROUTING_KEY,
                request
        );
    }
}