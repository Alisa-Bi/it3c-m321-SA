package ch.m321.chatapp.chat.service;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.messaging.MessageProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Prueft publishNewMessage(): id und sentAt muessen bei jedem Aufruf vom
 * Service selbst gesetzt werden (PLANUNG.md 3.4), unabhaengig davon, was
 * der Client mitschickt.
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageProducer messageProducer;

    // Der Client schickt weder id noch sentAt - der Service muss beide
    // trotzdem setzen, bevor die Nachricht an den Producer geht.
    @Test
    void publishNewMessage_assignsIdAndSentAt() {
        MessageService messageService = new MessageService(messageProducer);
        UUID roomId = UUID.randomUUID();
        NewMessageRequest incoming = new NewMessageRequest(null, roomId, "user-1", "Alice", "Hallo", null);

        messageService.publishNewMessage(incoming);

        ArgumentCaptor<NewMessageRequest> captor = ArgumentCaptor.forClass(NewMessageRequest.class);
        verify(messageProducer).publish(captor.capture());
        NewMessageRequest published = captor.getValue();

        assertThat(published.id()).isNotNull();
        assertThat(published.sentAt()).isNotNull();
        assertThat(published.roomId()).isEqualTo(roomId);
        assertThat(published.senderName()).isEqualTo("Alice");
        assertThat(published.content()).isEqualTo("Hallo");
    }
}
