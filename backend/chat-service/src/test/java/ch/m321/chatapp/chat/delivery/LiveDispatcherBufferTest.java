package ch.m321.chatapp.chat.delivery;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prueft den Teil des LiveDispatcher, der den Ringbuffer betrifft: Die Nachricht steht schon im
 * Puffer, wenn die Verbindungen des Raums abgefragt werden (Puffer vor Verteilung), und sentAt
 * wird aus dem echten JSON-Format ohne Verlust an Mikrosekunden gelesen. Ohne Spring und ohne RabbitMQ.
 */
class LiveDispatcherBufferTest {

    private final SseConnectionRegistry registry = mock(SseConnectionRegistry.class);
    private final RecentMessageBuffer buffer = new RecentMessageBuffer(10, 100);
    private final LiveDispatcher dispatcher = new LiveDispatcher(registry, new ObjectMapper(), buffer);

    // Erzeugt das JSON so, wie es der chat-service veroeffentlicht (derselbe Jackson-Converter).
    private String publishedJson(NewMessageRequest request) {
        Message message = new Jackson2JsonMessageConverter().toMessage(request, new MessageProperties());
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    // Beim Abfragen der Verbindungen steht die Nachricht schon im Puffer.
    @Test
    void dispatch_buffersMessageBeforeDistributing() {
        UUID roomId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String json = publishedJson(new NewMessageRequest(messageId, roomId, "sender-1", "Test", "Hallo",
                Instant.parse("2026-01-02T03:04:05.123456Z")));
        AtomicBoolean wasBufferedWhenDistributing = new AtomicBoolean(false);
        when(registry.connectionsOf(any(UUID.class))).thenAnswer(invocation -> {
            wasBufferedWhenDistributing.set(buffer.find(roomId, messageId).isPresent());
            return new ArrayList<>();
        });

        dispatcher.dispatch(json);

        assertThat(wasBufferedWhenDistributing).isTrue();
    }

    // sentAt wird mit allen sechs Nachkommastellen gelesen, der JSON-Text bleibt unveraendert.
    @Test
    void dispatch_readsSentAtWithMicrosecondPrecision() {
        UUID roomId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.parse("2026-11-13T17:31:11.468012Z");
        String json = publishedJson(new NewMessageRequest(messageId, roomId, "sender-1", "Test", "Hallo", sentAt));
        when(registry.connectionsOf(any(UUID.class))).thenReturn(new ArrayList<>());

        dispatcher.dispatch(json);

        BufferedMessage buffered = buffer.find(roomId, messageId).orElseThrow();
        assertThat(buffered.sentAt()).isEqualTo(sentAt);
        assertThat(buffered.json()).isEqualTo(json);
        assertThat(buffered.roomId()).isEqualTo(roomId);
    }

    // Auch ein Zeitstempel ohne Nachkommastellen und einer mit fuehrenden Nullen werden genau gelesen.
    @Test
    void dispatch_readsEdgeCasesOfSentAt() {
        UUID roomId = UUID.randomUUID();
        when(registry.connectionsOf(any(UUID.class))).thenReturn(new ArrayList<>());
        List<Instant> instants = new ArrayList<>();
        instants.add(Instant.ofEpochSecond(1_794_168_671L));
        instants.add(Instant.ofEpochSecond(1_794_168_671L, 1_000));
        instants.add(Instant.ofEpochSecond(1_794_168_671L, 999_999_000));
        List<UUID> ids = new ArrayList<>();

        for (Instant instant : instants) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            dispatcher.dispatch(publishedJson(new NewMessageRequest(id, roomId, "s", "n", "c", instant)));
        }

        for (int i = 0; i < instants.size(); i++) {
            assertThat(buffer.find(roomId, ids.get(i)).orElseThrow().sentAt()).isEqualTo(instants.get(i));
        }
    }

    // Ohne lesbares sentAt wird die Nachricht trotzdem verteilt, aber nicht gepuffert.
    @Test
    void dispatch_messageWithoutSentAt_isDistributedButNotBuffered() {
        UUID roomId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String json = "{\"id\":\"" + messageId + "\",\"roomId\":\"" + roomId + "\"}";
        AtomicBoolean distributed = new AtomicBoolean(false);
        when(registry.connectionsOf(any(UUID.class))).thenAnswer(invocation -> {
            distributed.set(true);
            return new ArrayList<>();
        });

        dispatcher.dispatch(json);

        assertThat(distributed).isTrue();
        assertThat(buffer.find(roomId, messageId)).isEmpty();
    }
}
