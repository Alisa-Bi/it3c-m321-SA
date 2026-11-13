package ch.m321.chatapp.chat.replay;

import ch.m321.chatapp.chat.delivery.BufferedMessage;
import ch.m321.chatapp.chat.delivery.RecentMessageBuffer;
import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.history.MessageHistoryRepository;
import ch.m321.chatapp.chat.history.MessagePosition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft den ReplayService mit einem echten PostgreSQL (Testcontainers) und einem eigenen Ringbuffer
 * je Test: Nachrichten nach dem Cursor in Reihenfolge, gleicher Zeitstempel (id als Tie-Breaker, auch
 * mit ids ab 8), Cursor nur im Ringbuffer, Zusammenfuehren ohne Doppelte, unbekannter Cursor (409),
 * Cursor eines anderen Raums (400) und Datenbankfehler (503). RabbitMQ wird nicht gebraucht.
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Testcontainers
class ReplayServiceTest {

    private static final Instant BASE_TIME = Instant.parse("2026-01-02T03:04:05.000000Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withInitScript("init-test.sql");

    // Verbindet die Anwendung mit dem Testcontainer statt mit dem Host "postgres".
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MessageHistoryRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Jackson2JsonMessageConverter jsonConverter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Fuegt eine Zeile ein, so wie es der batch-writer taete.
    private void insertRow(UUID roomId, UUID id, Instant sentAt, String content) {
        jdbcTemplate.update(
                "INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, roomId, "sender-1", "Test", content, OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC));
    }

    // Erzeugt einen Ringbuffer-Eintrag mit dem JSON, das der Veroeffentlichungsweg erzeugt haette.
    private BufferedMessage bufferedMessage(UUID roomId, UUID id, Instant sentAt, String content) {
        NewMessageRequest request = new NewMessageRequest(id, roomId, "sender-1", "Test", content, sentAt);
        Message message = jsonConverter.toMessage(request, new MessageProperties());
        return new BufferedMessage(id, roomId, sentAt, new String(message.getBody(), StandardCharsets.UTF_8));
    }

    // Ein Service mit eigenem, leerem Ringbuffer.
    private ReplayService serviceWith(RecentMessageBuffer buffer) {
        return new ReplayService(repository, buffer, jsonConverter);
    }

    // Liest den Inhalt jeder Nachricht aus dem JSON, in der Reihenfolge der Liste.
    private List<String> contents(List<BufferedMessage> messages) throws Exception {
        List<String> result = new ArrayList<>();
        for (BufferedMessage message : messages) {
            result.add(objectMapper.readTree(message.json()).get("content").asText());
        }
        return result;
    }

    // Liest den HTTP-Status aus einer ResponseStatusException.
    private int statusOf(Throwable exception) {
        HttpStatusCode code = ((ResponseStatusException) exception).getStatusCode();
        return code.value();
    }

    // Nur Nachrichten nach dem Cursor, aufsteigend nach Zeit; der Cursor selbst ist nicht dabei.
    @Test
    void missedMessages_returnsOnlyMessagesAfterCursorInOrder() throws Exception {
        UUID room = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(1), "m1");
        insertRow(room, cursorId, BASE_TIME.plusMillis(2), "m2");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(5), "m5");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(3), "m3");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(4), "m4");

        List<BufferedMessage> missed = serviceWith(new RecentMessageBuffer(10, 100)).missedMessages(room, cursorId);

        assertThat(contents(missed)).containsExactly("m3", "m4", "m5");
    }

    // Der Cursor ist die letzte Nachricht: nichts fehlt.
    @Test
    void missedMessages_cursorIsLastMessage_returnsEmptyList() {
        UUID room = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(1), "m1");
        insertRow(room, cursorId, BASE_TIME.plusMillis(2), "m2");

        assertThat(serviceWith(new RecentMessageBuffer(10, 100)).missedMessages(room, cursorId)).isEmpty();
    }

    // Nachrichten anderer Raeume fehlen nie im Ergebnis, auch wenn sie spaeter liegen.
    @Test
    void missedMessages_excludesOtherRooms() throws Exception {
        UUID room = UUID.randomUUID();
        UUID otherRoom = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        insertRow(room, cursorId, BASE_TIME.plusMillis(1), "own-cursor");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(2), "own-later");
        insertRow(otherRoom, UUID.randomUUID(), BASE_TIME.plusMillis(3), "foreign");

        List<BufferedMessage> missed = serviceWith(new RecentMessageBuffer(10, 100)).missedMessages(room, cursorId);

        assertThat(contents(missed)).containsExactly("own-later");
    }

    // Gleicher sent_at: Die id entscheidet, Byte fuer Byte ohne Vorzeichen, in Datenbank und Ringbuffer gleich.
    // ids ab 8 muessen hinter ids von 0 bis 7 stehen (java.util.UUID.compareTo ordnete sie davor).
    @Test
    void missedMessages_sameTimestamp_ordersByIdLikeThePostgresDatabase() throws Exception {
        UUID room = UUID.randomUUID();
        Instant sameTime = BASE_TIME.plusMillis(10);
        UUID cursorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        insertRow(room, cursorId, sameTime, "cursor");
        insertRow(room, UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffffe"), sameTime, "id-ffff");
        insertRow(room, UUID.fromString("80000000-0000-0000-0000-000000000000"), sameTime, "id-8000");
        insertRow(room, UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"), sameTime, "id-7fff");
        // Diese Nachricht liegt nur im Ringbuffer und muss richtig einsortiert werden.
        RecentMessageBuffer buffer = new RecentMessageBuffer(10, 100);
        buffer.add(bufferedMessage(room, UUID.fromString("c0000000-0000-0000-0000-000000000000"), sameTime, "id-c000-buffer"));

        List<BufferedMessage> missed = serviceWith(buffer).missedMessages(room, cursorId);

        assertThat(contents(missed)).containsExactly("id-7fff", "id-8000", "id-c000-buffer", "id-ffff");
    }

    // Der Cursor steht nur im Ringbuffer (noch nicht gespeichert): Er liefert die Zeit fuer die Abfrage,
    // spaetere Zeilen aus der Datenbank und spaetere Eintraege aus dem Ringbuffer werden zusammengefuehrt.
    @Test
    void missedMessages_cursorOnlyInBuffer_mergesDatabaseAndBuffer() throws Exception {
        UUID room = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(1), "stored-before-cursor");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(3), "stored-after-cursor");
        RecentMessageBuffer buffer = new RecentMessageBuffer(10, 100);
        buffer.add(bufferedMessage(room, cursorId, BASE_TIME.plusMillis(2), "buffer-cursor"));
        buffer.add(bufferedMessage(room, UUID.randomUUID(), BASE_TIME.plusMillis(4), "buffer-after-cursor"));

        List<BufferedMessage> missed = serviceWith(buffer).missedMessages(room, cursorId);

        assertThat(contents(missed)).containsExactly("stored-after-cursor", "buffer-after-cursor");
    }

    // Steht eine Nachricht in Datenbank und Ringbuffer, erscheint sie genau einmal.
    @Test
    void missedMessages_messageInBothSources_appearsOnce() throws Exception {
        UUID room = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        UUID sharedId = UUID.randomUUID();
        insertRow(room, cursorId, BASE_TIME.plusMillis(1), "cursor");
        insertRow(room, sharedId, BASE_TIME.plusMillis(2), "shared");
        RecentMessageBuffer buffer = new RecentMessageBuffer(10, 100);
        buffer.add(bufferedMessage(room, sharedId, BASE_TIME.plusMillis(2), "shared"));
        buffer.add(bufferedMessage(room, cursorId, BASE_TIME.plusMillis(1), "cursor"));

        List<BufferedMessage> missed = serviceWith(buffer).missedMessages(room, cursorId);

        assertThat(contents(missed)).containsExactly("shared");
    }

    // Ein Cursor, den es nirgends gibt, ergibt 409.
    @Test
    void missedMessages_unknownCursor_answers409() {
        ReplayService service = serviceWith(new RecentMessageBuffer(10, 100));

        assertThatThrownBy(() -> service.missedMessages(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(CursorNotFoundException.class)
                .satisfies(exception -> assertThat(statusOf(exception)).isEqualTo(409));
    }

    // Ein Cursor, der in der Datenbank zu einem anderen Raum gehoert, ergibt 400.
    @Test
    void missedMessages_cursorOfAnotherRoomInDatabase_answers400() {
        UUID room = UUID.randomUUID();
        UUID otherRoom = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        insertRow(otherRoom, cursorId, BASE_TIME.plusMillis(1), "foreign");
        ReplayService service = serviceWith(new RecentMessageBuffer(10, 100));

        assertThatThrownBy(() -> service.missedMessages(room, cursorId))
                .isInstanceOf(InvalidCursorException.class)
                .satisfies(exception -> assertThat(statusOf(exception)).isEqualTo(400));
    }

    // Ein Cursor, der nur im Ringbuffer eines anderen Raums liegt, gilt im angefragten Raum als nicht gefunden (409).
    @Test
    void missedMessages_cursorOnlyInBufferOfAnotherRoom_answers409() {
        UUID room = UUID.randomUUID();
        UUID otherRoom = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        RecentMessageBuffer buffer = new RecentMessageBuffer(10, 100);
        buffer.add(bufferedMessage(otherRoom, cursorId, BASE_TIME.plusMillis(1), "foreign"));
        ReplayService service = serviceWith(buffer);

        assertThatThrownBy(() -> service.missedMessages(room, cursorId))
                .isInstanceOf(CursorNotFoundException.class);
    }

    // Ist die Datenbank nicht erreichbar, ergibt das 503, auch wenn der Cursor im Ringbuffer steht.
    @Test
    void missedMessages_databaseDown_answers503EvenIfCursorIsInBuffer() {
        UUID room = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        RecentMessageBuffer buffer = new RecentMessageBuffer(10, 100);
        buffer.add(bufferedMessage(room, cursorId, BASE_TIME.plusMillis(1), "buffered"));
        DriverManagerDataSource unreachable = new DriverManagerDataSource("jdbc:postgresql://localhost:1/chat", "chatapp", "x");
        MessageHistoryRepository brokenRepository = new MessageHistoryRepository(new JdbcTemplate(unreachable));
        ReplayService service = new ReplayService(brokenRepository, buffer, jsonConverter);

        assertThatThrownBy(() -> service.missedMessages(room, cursorId))
                .isInstanceOf(ReplayUnavailableException.class)
                .satisfies(exception -> assertThat(statusOf(exception)).isEqualTo(503));
    }

    // resolveCursor allein liefert die Position aus der Datenbank mit Mikrosekunden.
    @Test
    void resolveCursor_returnsPositionFromDatabase() {
        UUID room = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        Instant sentAt = Instant.parse("2026-03-04T05:06:07.123456Z");
        insertRow(room, cursorId, sentAt, "cursor");

        MessagePosition position = serviceWith(new RecentMessageBuffer(10, 100)).resolveCursor(room, cursorId);

        assertThat(position.sentAt()).isEqualTo(sentAt);
        assertThat(position.roomId()).isEqualTo(room);
        assertThat(position.id()).isEqualTo(cursorId);
    }
}
