package ch.m321.chatapp.chat.replay;

import ch.m321.chatapp.chat.delivery.SseConnectionRegistry;
import ch.m321.chatapp.chat.delivery.SseTestClient;
import ch.m321.chatapp.chat.history.MessageHistoryRepository;
import ch.m321.chatapp.chat.history.MessagePosition;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Prueft die Grenze des Zwischenspeichers je Verbindung (CHAT_SSE_REPLAY_BUFFER_MAX, hier 5): Treffen
 * waehrend des Nachholens mehr Live-Nachrichten ein, wird die Verbindung beendet und ausgetragen. Der Client
 * verbindet sich danach neu und holt mit Last-Event-ID nach (docs/spec-live-zustellung.md, Abschnitt 5.3).
 * Eine Nachhol-Verbindung stoert andere Verbindungen im selben Raum nicht.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "chat.sse.replay-buffer-max=5")
@Testcontainers
class ReplayBufferOverflowIntegrationTest {

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

    @SpyBean
    private MessageHistoryRepository repository;

    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    // Sendet eine Nachricht ueber POST /messages und prueft, dass sie angenommen wurde (202).
    private void postMessage(String baseUrl, UUID roomId, String content) throws Exception {
        String json = "{\"roomId\":\"" + roomId + "\",\"senderId\":\"sender-1\","
                + "\"senderName\":\"Test\",\"content\":\"" + content + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
    }

    // Mehr Live-Nachrichten als der Zwischenspeicher fasst: Die Nachhol-Verbindung wird beendet und
    // ausgetragen, eine zweite, normale Verbindung im selben Raum bekommt weiter alles.
    @Test
    void moreLiveMessagesThanBufferHolds_closesOnlyTheReplayingConnection() throws Exception {
        String baseUrl = "http://localhost:" + port;
        UUID room = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) VALUES (?, ?, ?, ?, ?, ?)",
                cursorId, room, "sender-1", "Test", "cursor", OffsetDateTime.ofInstant(BASE_TIME, ZoneOffset.UTC));

        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        doAnswer(invocation -> {
            queryStarted.countDown();
            releaseQuery.await(30, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(repository).findAfter(any(UUID.class), any(MessagePosition.class));

        SseTestClient liveClient = new SseTestClient(httpClient, baseUrl, room);
        SseTestClient replayClient = new SseTestClient(httpClient, baseUrl, room, cursorId);
        try {
            assertThat(queryStarted.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.connectionCount(room)).isEqualTo(2);

            for (int i = 0; i < 8; i++) {
                postMessage(baseUrl, room, "live-" + i);
            }

            // Der Zwischenspeicher fasst 5 Nachrichten, die sechste beendet die Nachhol-Verbindung.
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(registry.connectionCount(room)).isEqualTo(1));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(liveClient.eventCount()).isEqualTo(8));
            assertThat(replayClient.eventCount()).isZero();

            // Die Nachhol-Aufgabe laeuft danach ins Leere, ohne Fehler und ohne die andere Verbindung zu stoeren.
            releaseQuery.countDown();
            postMessage(baseUrl, room, "live-after");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(liveClient.eventCount()).isEqualTo(9));
            assertThat(registry.connectionCount(room)).isEqualTo(1);
        } finally {
            releaseQuery.countDown();
            liveClient.close();
            replayClient.close();
        }
    }
}
