package ch.m321.chatapp.chat.messaging;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Liest neue Nachrichten aus RabbitMQ und speichert sie in PostgreSQL.
 *
 * Das ist die einzige Stelle im Chat-Service, an der eine Nachricht
 * tatsaechlich gespeichert wird - der Controller nimmt die Anfrage nur
 * entgegen und schickt sie ueber den MessageProducer los (siehe
 * MessageService.publishNewMessage). Diese Trennung entspricht dem
 * Nachrichtenfluss aus der Planung.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MessageConsumer {

    private final MessageService messageService;

    @RabbitListener(queues = RabbitMqConfig.MESSAGE_QUEUE)
    public void onMessage(NewMessageRequest request) {
        log.info("Nachricht aus RabbitMQ empfangen fuer Chatraum {}", request.roomId());
        messageService.createMessage(request);
        // TODO: Ergebnis per WebSocket an alle Teilnehmer des Chatraums senden,
        // sobald die Session-Verwaltung im ChatWebSocketHandler existiert.
    }
}
