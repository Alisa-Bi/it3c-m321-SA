package ch.m321.chatapp.chat.replay;

import ch.m321.chatapp.chat.delivery.RecentMessageBuffer;
import ch.m321.chatapp.chat.delivery.SseConnectionRegistry;
import ch.m321.chatapp.chat.delivery.SseTestClient;
import ch.m321.chatapp.chat.history.MessageHistoryRepository;
import ch.m321.chatapp.chat.history.MessagePosition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Prueft den Uebergang vom Nachholen zum Live-Betrieb am Endpunkt GET /stream mit echtem RabbitMQ und
 * echtem PostgreSQL (docs/spec-live-zustellung.md, Abschnitt 5.3).
 *
 * Normalfall: Verpasste Nachrichten aus der Datenbank, danach Live-Nachrichten. Verzoegerung der
 * Datenbankabfrage: Zehn Live-Nachrichten treffen waehrend des Nachholens ein, keine geht verloren, keine
 * erscheint doppelt, die Reihenfolge stimmt, danach funktioniert die Live-Zustellung. Verzoegerung der
 * Cursor-Pruefung: Nachrichten, die im Schnappschuss und im Zwischenspeicher stehen, kommen genau einmal.
 * Fehlerfaelle: ungueltige, unbekannte und fremde Last-Event-ID ergeben 400 beziehungsweise 409, und die
 * Verbindung wird wieder abgemeldet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReplayHandoverTest {

    private static final Instant BASE_TIME = Instant.parse("2026-01-02T03:04:05.000000Z");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withInitScript("init-test.sql");

    // Verbindet die Anwendung mit den Testcontainern statt mit den Hosts "rabbitmq" und "postgres".
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SseConnectionRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Der Spion leitet alle Aufrufe an das echte Repository weiter. Einzelne Methoden werden gezielt verzoegert.
    @SpyBean
    private MessageHistoryRepository repository;

    @Autowired
    private RecentMessageBuffer buffer;

    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Adresse der Anwendung.
    private String baseUrl() {
        return "http://localhost:" + port;
    }

    // Fuegt eine Zeile ein, so wie es der batch-writer taete.
    private void insertRow(UUID roomId, UUID id, Instant sentAt, String content) {
        jdbcTemplate.update(
                "INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, roomId, "sender-1", "Test", content, OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC));
    }

    // Sendet eine Nachricht ueber POST /messages und prueft, dass sie angenommen wurde (202).
    private void postMessage(UUID roomId, String content) throws Exception {
        String json = "{\"roomId\":\"" + roomId + "\",\"senderId\":\"sender-1\","
                + "\"senderName\":\"Test\",\"content\":\"" + content + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
    }

    // Liest den Inhalt jedes empfangenen Ereignisses, in der Reihenfolge des Empfangs.
    private List<String> contentsOf(SseTestClient client) throws Exception {
        List<String> result = new ArrayList<>();
        for (SseTestClient.ReceivedEvent event : client.events) {
            result.add(objectMapper.readTree(event.data).get("content").asText());
        }
        return result;
    }

    // Prueft, dass keine id doppelt empfangen wurde.
    private void assertNoDuplicateIds(SseTestClient client) {
        Set<String> seen = new HashSet<>();
        for (SseTestClient.ReceivedEvent event : client.events) {
            assertThat(seen.add(event.id)).as("id %s nur einmal", event.id).isTrue();
        }
    }

    // Schickt eine GET-Anfrage an /stream und gibt nur den Status zurueck (fuer Fehlerfaelle).
    private int statusOfStream(UUID roomId, String lastEventIdText) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + "/stream?roomId=" + roomId)).GET();
        if (lastEventIdText != null) {
            builder.header("Last-Event-ID", lastEventIdText);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    // Normalfall: erst die verpassten Nachrichten aus der Datenbank in Reihenfolge, dann live.
    @Test
    void reconnect_sendsMissedMessagesThenSwitchesToLive() throws Exception {
        UUID room = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        insertRow(room, cursorId, BASE_TIME, "cursor");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(1), "m1");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(2), "m2");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(3), "m3");

        SseTestClient client = new SseTestClient(httpClient, baseUrl(), room, cursorId);
        try {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(client.eventCount()).isEqualTo(3));
            postMessage(room, "live-1");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(client.eventCount()).isEqualTo(4));

            assertThat(contentsOf(client)).containsExactly("m1", "m2", "m3", "live-1");
            assertNoDuplicateIds(client);
            assertThat(client.formatViolations).isEmpty();
        } finally {
            client.close();
        }
    }

    // Zehn Live-Nachrichten waehrend einer verzoegerten Datenbankabfrage: keine geht verloren, keine doppelt,
    // Reihenfolge stimmt, danach funktioniert die Live-Zustellung. Der Schnappschuss ist da schon erstellt,
    // die Nachrichten liegen nur im Zwischenspeicher der Verbindung.
    @Test
    void liveMessagesDuringDatabaseQuery_areNotLostOrDuplicated() throws Exception {
        UUID room = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        insertRow(room, cursorId, BASE_TIME, "cursor");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(1), "m1");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(2), "m2");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(3), "m3");

        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        doAnswer(invocation -> {
            queryStarted.countDown();
            releaseQuery.await(30, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(repository).findAfter(any(UUID.class), any(MessagePosition.class));

        SseTestClient client = new SseTestClient(httpClient, baseUrl(), room, cursorId);
        try {
            assertThat(queryStarted.await(15, TimeUnit.SECONDS)).as("Datenbankabfrage hat begonnen").isTrue();
            for (int i = 0; i < 10; i++) {
                postMessage(room, "live-" + i);
            }
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(buffer.roomSize(room)).isEqualTo(10));
            assertThat(client.eventCount()).as("waehrend des Nachholens wird nichts gesendet").isZero();

            releaseQuery.countDown();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(client.eventCount()).isEqualTo(13));

            postMessage(room, "live-after");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(client.eventCount()).isEqualTo(14));

            List<String> expected = new ArrayList<>();
            expected.add("m1");
            expected.add("m2");
            expected.add("m3");
            for (int i = 0; i < 10; i++) {
                expected.add("live-" + i);
            }
            expected.add("live-after");
            assertThat(contentsOf(client)).containsExactlyElementsOf(expected);
            assertNoDuplicateIds(client);
            assertThat(client.formatViolations).isEmpty();
        } finally {
            releaseQuery.countDown();
            client.close();
        }
    }

    // Nachrichten, die schon im Schnappschuss des Ringbuffers und im Zwischenspeicher der Verbindung stehen,
    // kommen genau einmal. Dafuer wird die Cursor-Pruefung verzoegert: Die Verbindung ist dann schon angemeldet
    // (Schritt 1), der Schnappschuss entsteht aber erst nach den Live-Nachrichten. Der Client oeffnet die
    // Verbindung in einem eigenen Thread, weil die Antwort erst nach der Cursor-Pruefung kommt.
    @Test
    void messagesInSnapshotAndPendingBuffer_areSentOnce() throws Exception {
        UUID room = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        insertRow(room, cursorId, BASE_TIME, "cursor");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(1), "m1");
        insertRow(room, UUID.randomUUID(), BASE_TIME.plusMillis(2), "m2");

        CountDownLatch cursorCheckStarted = new CountDownLatch(1);
        CountDownLatch releaseCursorCheck = new CountDownLatch(1);
        doAnswer(invocation -> {
            cursorCheckStarted.countDown();
            releaseCursorCheck.await(30, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(repository).findPosition(any(UUID.class));

        CompletableFuture<SseTestClient> opening = CompletableFuture.supplyAsync(() -> {
            try {
                return new SseTestClient(httpClient, baseUrl(), room, cursorId);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        try {
            assertThat(cursorCheckStarted.await(15, TimeUnit.SECONDS)).as("Cursor-Pruefung hat begonnen").isTrue();
            assertThat(registry.connectionCount(room)).as("Verbindung ist schon angemeldet").isEqualTo(1);
            for (int i = 0; i < 5; i++) {
                postMessage(room, "live-" + i);
            }
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(buffer.roomSize(room)).isEqualTo(5));

            releaseCursorCheck.countDown();
            SseTestClient client = opening.get(15, TimeUnit.SECONDS);
            try {
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(client.eventCount()).isGreaterThanOrEqualTo(7));
                // Eine weitere Nachricht laeuft hinter allen anderen durch dieselbe Leitung. Kaeme ein
                // Doppeltes, waere es vor ihr da.
                postMessage(room, "live-after");
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(client.eventCount()).isGreaterThanOrEqualTo(8));

                assertThat(contentsOf(client)).containsExactly("m1", "m2", "live-0", "live-1", "live-2", "live-3", "live-4", "live-after");
                assertNoDuplicateIds(client);
            } finally {
                client.close();
            }
        } finally {
            releaseCursorCheck.countDown();
        }
    }

    // Eine Last-Event-ID, die keine UUID ist, ergibt 400, und es bleibt keine Verbindung zurueck.
    @Test
    void invalidLastEventId_answers400() throws Exception {
        UUID room = UUID.randomUUID();

        assertThat(statusOfStream(room, "keine-uuid")).isEqualTo(400);

        assertThat(registry.connectionCount(room)).isZero();
    }

    // Eine Last-Event-ID, die es weder in der Datenbank noch im Ringbuffer gibt, ergibt 409.
    @Test
    void unknownLastEventId_answers409AndLeavesNoConnection() throws Exception {
        UUID room = UUID.randomUUID();

        assertThat(statusOfStream(room, UUID.randomUUID().toString())).isEqualTo(409);

        assertThat(registry.connectionCount(room)).isZero();
    }

    // Eine Last-Event-ID, die in der Datenbank zu einem anderen Raum gehoert, ergibt 400.
    @Test
    void lastEventIdOfAnotherRoom_answers400AndLeavesNoConnection() throws Exception {
        UUID room = UUID.randomUUID();
        UUID otherRoom = UUID.randomUUID();
        UUID foreignId = UUID.randomUUID();
        insertRow(otherRoom, foreignId, BASE_TIME, "foreign");

        assertThat(statusOfStream(room, foreignId.toString())).isEqualTo(400);

        assertThat(registry.connectionCount(room)).isZero();
    }
}
