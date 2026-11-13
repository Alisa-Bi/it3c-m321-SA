package ch.m321.chatapp.chat.service;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.messaging.MessageProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Zusaetzliche Pruefung S10 (docs/spec-live-zustellung.md, Abschnitt 8):
 * sentAt hat keine Anteile unterhalb der Mikrosekunde, weil PostgreSQL nur
 * Mikrosekunden speichert und Live-Zustellung und Datenbank denselben
 * Zeitstempel haben muessen.
 */
@ExtendWith(MockitoExtension.class)
class SentAtPrecisionTest {

    @Mock
    private MessageProducer messageProducer;

    // Viele Nachrichten statt einer einzigen: Je nach Betriebssystem hat
    // Instant.now() gelegentlich "glatte" Werte. Mit 1000 Nachrichten faellt
    // ein fehlendes Kuerzen sicher auf.
    @Test
    void sentAt_hasNoPartBelowOneMicrosecond() {
        MessageService messageService = new MessageService(messageProducer, 4096);
        NewMessageRequest incoming = new NewMessageRequest(null, UUID.randomUUID(), "user-1", "Alice", "Hallo", null);

        for (int i = 0; i < 1000; i++) {
            messageService.publishNewMessage(incoming);
        }

        ArgumentCaptor<NewMessageRequest> captor = ArgumentCaptor.forClass(NewMessageRequest.class);
        verify(messageProducer, times(1000)).publish(captor.capture());
        List<NewMessageRequest> publishedMessages = captor.getAllValues();
        for (NewMessageRequest published : publishedMessages) {
            assertThat(published.sentAt()).isEqualTo(published.sentAt().truncatedTo(ChronoUnit.MICROS));
        }
    }
}
