package ch.m321.chatapp.chat.service;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.messaging.MessageProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Fachlogik rund um das Entgegennehmen neuer Chatnachrichten.
 *
 * chat-service schreibt nie in die Datenbank (siehe docs/spec-batch-writer.md,
 * Abschnitt 1); lesend greift er nur beim Nachholen darauf zu (Paket history).
 * Diese Klasse validiert nur, vergibt id und sentAt (PLANUNG.md 3.4) und gibt
 * die fertige Nachricht an RabbitMQ weiter.
 * batch-writer ist der einzige Datenbank-Schreiber.
 */
@Service
@Slf4j
public class MessageService {

    private final MessageProducer messageProducer;
    private final int maxContentBytes;

    // Der Konstruktor ist von Hand geschrieben, weil neben dem Producer auch
    // die konfigurierte Groessengrenze (chat.max-content-bytes) uebergeben wird.
    public MessageService(MessageProducer messageProducer,
                          @Value("${chat.max-content-bytes}") int maxContentBytes) {
        this.messageProducer = messageProducer;
        this.maxContentBytes = maxContentBytes;
    }

    // Validiert die Eingabe, vergibt id und sentAt und veroeffentlicht die
    // fertige Nachricht. Wird vom Controller fuer jede eingehende Anfrage aufgerufen.
    public void publishNewMessage(NewMessageRequest request) {
        validate(request);

        // sentAt wird auf Mikrosekunden gekuerzt, weil PostgreSQL nur
        // Mikrosekunden speichert. So sind Zeitstempel in der Live-Zustellung
        // und in der Datenbank identisch (siehe Spezifikation, Abschnitt 5.2).
        Instant sentAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        NewMessageRequest readyToSend = new NewMessageRequest(
                UUID.randomUUID(),
                request.roomId(),
                request.senderId(),
                request.senderName(),
                request.content(),
                sentAt);

        publishOrFail(readyToSend);
    }

    // Prueft Pflichtfelder und die maximale Groesse des Inhalts. Zu grosse
    // Inhalte werden abgelehnt und nie abgeschnitten, damit nichts stillschweigend
    // verloren geht. Die Groesse wird in UTF-8-Bytes gemessen, nicht in Zeichen.
    private void validate(NewMessageRequest request) {
        if (request.roomId() == null || request.senderId() == null || request.senderId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId und senderId sind erforderlich");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nachricht darf nicht leer sein");
        }
        int contentBytes = request.content().getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > maxContentBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nachricht ist zu gross (maximal " + maxContentBytes + " Bytes)");
        }
    }

    // Veroeffentlicht die Nachricht und uebersetzt einen RabbitMQ-Fehler in
    // HTTP 503. Ohne diese Uebersetzung wuerde der Client einen unklaren
    // Serverfehler (500) sehen.
    private void publishOrFail(NewMessageRequest message) {
        try {
            messageProducer.publish(message);
        } catch (AmqpException exception) {
            log.error("Publishing message {} failed", message.id(), exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Nachricht konnte nicht veroeffentlicht werden");
        }
    }
}
