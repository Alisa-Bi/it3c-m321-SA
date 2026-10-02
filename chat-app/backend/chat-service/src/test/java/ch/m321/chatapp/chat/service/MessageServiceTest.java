package ch.m321.chatapp.chat.service;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.mapper.MessageMapper;
import ch.m321.chatapp.chat.messaging.MessageProducer;
import ch.m321.chatapp.chat.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Prueft publishNewMessage(): jede Nachricht muss mit einer messageId an
 * den Producer weitergegeben werden - auch dann, wenn der REST-Client keine
 * mitschickt. Das ist die Grundlage fuer die spaetere Idempotenz im
 * batch-writer (siehe docs/spec-batch-writer.md, Abschnitt 2 und 5).
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private MessageProducer messageProducer;

    // Haeufigster Fall: der REST-Client schickt (wie bisher) kein messageId
    // mit - der Service muss trotzdem eines erzeugen, bevor er den Producer ruft.
    @Test
    void publishNewMessage_generatesMessageIdWhenMissing() {
        MessageService messageService = new MessageService(messageRepository, messageMapper, messageProducer);
        UUID roomId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        NewMessageRequest requestWithoutId = new NewMessageRequest(null, roomId, senderId, "Hallo");

        messageService.publishNewMessage(requestWithoutId);

        ArgumentCaptor<NewMessageRequest> captor = ArgumentCaptor.forClass(NewMessageRequest.class);
        verify(messageProducer).publish(captor.capture());
        NewMessageRequest published = captor.getValue();

        assertThat(published.messageId()).isNotNull();
        assertThat(published.roomId()).isEqualTo(roomId);
        assertThat(published.senderId()).isEqualTo(senderId);
        assertThat(published.content()).isEqualTo("Hallo");
    }

    // Falls ein Aufrufer bereits ein messageId mitgibt, darf der Service es
    // nicht durch ein neues ersetzen - sonst waere die Idempotenz kaputt.
    @Test
    void publishNewMessage_keepsExistingMessageId() {
        MessageService messageService = new MessageService(messageRepository, messageMapper, messageProducer);
        UUID existingMessageId = UUID.randomUUID();
        NewMessageRequest requestWithId =
                new NewMessageRequest(existingMessageId, UUID.randomUUID(), UUID.randomUUID(), "Hallo");

        messageService.publishNewMessage(requestWithId);

        ArgumentCaptor<NewMessageRequest> captor = ArgumentCaptor.forClass(NewMessageRequest.class);
        verify(messageProducer).publish(captor.capture());

        assertThat(captor.getValue().messageId()).isEqualTo(existingMessageId);
    }
}
