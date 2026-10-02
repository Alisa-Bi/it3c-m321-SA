package ch.m321.chatapp.chat.service;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.messaging.MessageProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Fachlogik rund um das Entgegennehmen neuer Chatnachrichten.
 *
 * chat-service speichert nichts selbst (siehe docs/spec-batch-writer.md,
 * Abschnitt 1) - diese Klasse validiert nur, vergibt id und sentAt
 * (PLANUNG.md 3.4) und gibt die fertige Nachricht an RabbitMQ weiter.
 * batch-writer ist der einzige Datenbank-Schreiber.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageService {

    private final MessageProducer messageProducer;

    // Validiert die Eingabe, vergibt id und sentAt und veroeffentlicht die
    // fertige Nachricht. Wird vom Controller fuer jede eingehende Anfrage aufgerufen.
    public void publishNewMessage(NewMessageRequest request) {
        if (request.roomId() == null || request.senderId() == null || request.senderId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId und senderId sind erforderlich");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nachricht darf nicht leer sein");
        }

        NewMessageRequest readyToSend = new NewMessageRequest(
                UUID.randomUUID(),
                request.roomId(),
                request.senderId(),
                request.senderName(),
                request.content(),
                Instant.now());

        messageProducer.publish(readyToSend);
    }
}
