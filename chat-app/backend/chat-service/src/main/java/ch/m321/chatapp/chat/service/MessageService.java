package ch.m321.chatapp.chat.service;

import ch.m321.chatapp.chat.dto.MessageDto;
import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.entity.Message;
import ch.m321.chatapp.chat.entity.MessageStatus;
import ch.m321.chatapp.chat.mapper.MessageMapper;
import ch.m321.chatapp.chat.messaging.MessageProducer;
import ch.m321.chatapp.chat.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fachlogik rund um Chatnachrichten.
 *
 * Lesen (Historie) passiert synchron direkt aus der Datenbank. Schreiben
 * passiert bewusst asynchron, entsprechend dem Nachrichtenfluss aus der
 * Planung (Chat Service -> RabbitMQ -> Message Consumer -> Speicherung):
 * publishNewMessage() prueft nur die Eingabe und gibt sie an RabbitMQ
 * weiter; createMessage() speichert tatsaechlich in PostgreSQL und wird
 * ausschliesslich vom MessageConsumer aufgerufen, nachdem die Nachricht
 * aus der Queue gelesen wurde.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final MessageProducer messageProducer;

    public List<MessageDto> getMessageHistory(UUID roomId) {
        List<Message> messages = messageRepository.findByRoomIdOrderByCreatedAtAsc(roomId);
        List<MessageDto> result = new ArrayList<>();
        for (Message message : messages) {
            result.add(messageMapper.toDto(message));
        }
        return result;
    }

    // Wird vom Controller aufgerufen. Prueft nur die Eingabe und uebergibt
    // sie an RabbitMQ - die eigentliche Speicherung passiert entkoppelt im
    // MessageConsumer, damit der Chat-Service unter Last reaktionsfaehig
    // bleibt und der Sender nicht auf den Datenbank-Schreibvorgang warten muss.
    public void publishNewMessage(NewMessageRequest request) {
        if (request.roomId() == null || request.senderId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId und senderId sind erforderlich");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nachricht darf nicht leer sein");
        }
        messageProducer.publish(request);
    }

    // Speichert eine neue Nachricht mit Status SENT. Wird ausschliesslich
    // vom MessageConsumer aufgerufen, nachdem eine Nachricht aus RabbitMQ
    // gelesen wurde. Zustellung/Lesen (DELIVERED/READ) folgen in einem
    // spaeteren Schritt.
    public MessageDto createMessage(NewMessageRequest request) {
        Message newMessage = new Message();
        newMessage.setRoomId(request.roomId());
        newMessage.setSenderId(request.senderId());
        newMessage.setContent(request.content());
        newMessage.setCreatedAt(Instant.now());
        newMessage.setStatus(MessageStatus.SENT);
        Message savedMessage = messageRepository.save(newMessage);
        log.info("Neue Nachricht gespeichert: {}", savedMessage.getId());
        return messageMapper.toDto(savedMessage);
    }
}
