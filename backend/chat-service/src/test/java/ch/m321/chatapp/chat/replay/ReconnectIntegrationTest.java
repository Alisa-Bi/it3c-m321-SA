package ch.m321.chatapp.chat.replay;

import ch.m321.chatapp.chat.ChatServiceApplication;
import ch.m321.chatapp.chat.delivery.SseTestClient;
import ch.m321.chatapp.chat.dto.NewMessageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Szenario S7 (docs/spec-live-zustellung.md, Abschnitt 8) mit echtem RabbitMQ und echtem PostgreSQL: Ein
 * Client empfaengt eine Nachricht und trennt die Verbindung. Waehrend der Unterbrechung gehen 50 weitere
 * Nachrichten ein. Nach 10 Sekunden verbindet sich der Client mit der Last-Event-ID der ersten Nachricht
 * erneut. Er muss genau die 50 fehlenden Nachrichten in der richtigen Reihenfolge erhalten, keine doppelt, und
 * danach eine neue Nachricht live.
 *
 * Zwei Varianten, jede mit einer eigenen Instanz des chat-service:
 * 1. In PostgreSQL: Weil im Test kein batch-writer laeuft, schreibt der Test die Zeilen selbst per SQL. Der
 *    Ringbuffer ist auf 10 Nachrichten verkleinert. Die erste Nachricht und die ersten 40 der fehlenden Nachrichten
 *    stehen deshalb nur in der Datenbank.
 * 2. Nur im Ringbuffer: Es gibt keine Zeilen in der Datenbank, der Ringbuffer ist gross genug.
 */
@Testcontainers
class ReconnectIntegrationTest {

    private static final int MISSED_MESSAGES = 50;
    private static final Duration PAUSE_WHILE_DISCONNECTED = Duration.ofSeconds(10);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withInitScript("init-test.sql");

    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // Startet eine Instanz des chat-service gegen die Testcontainer. Die Grenze des Ringbuffers ist einstellbar.
    private ConfigurableApplicationContext startInstance(int ringbufferSize) {
        return new SpringApplicationBuilder(ChatServiceApplication.class).run(
                "--server.port=0",
                "--spring.rabbitmq.host=" + rabbitmq.getHost(),
                "--spring.rabbitmq.port=" + rabbitmq.getAmqpPort(),
                "--spring.rabbitmq.username=" + rabbitmq.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbitmq.getAdminPassword(),
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--chat.ringbuffer.per-room=" + ringbufferSize,
                "--chat.ringbuffer.total=" + ringbufferSize);
    }

    // Adresse einer Instanz, aus dem zufaelligen Port.
    private String baseUrl(ConfigurableApplicationContext context) {
        return "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    // Sendet eine Nachricht ueber POST /messages und prueft, dass sie angenommen wurde (202).
    private void postMessage(String baseUrl, UUID roomId, String senderId, String content) throws Exception {
        String json = "{\"roomId\":\"" + roomId + "\",\"senderId\":\"" + senderId
                + "\",\"senderName\":\"Test\",\"content\":\"" + content + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
    }

    // Schreibt die Nachrichten so in die Tabelle message, wie es der batch-writer taete.
    private void storeInDatabase(JdbcTemplate jdbcTemplate, List<SseTestClient.ReceivedEvent> events) throws Exception {
        for (SseTestClient.ReceivedEvent event : events) {
            NewMessageRequest message = objectMapper.readValue(event.data, NewMessageRequest.class);
            jdbcTemplate.update(
                    "INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) VALUES (?, ?, ?, ?, ?, ?)",
                    message.id(), message.roomId(), message.senderId(), message.senderName(), message.content(),
                    OffsetDateTime.ofInstant(message.sentAt(), ZoneOffset.UTC));
        }
    }

    // Fuehrt S7 aus. Mit storeRows werden die Nachrichten vor der Neuverbindung in die Datenbank geschrieben.
    private void runScenario(ConfigurableApplicationContext context, boolean storeRows) throws Exception {
        String baseUrl = baseUrl(context);
        UUID room = UUID.randomUUID();

        // Ein Beobachter bleibt verbunden und sieht alle Nachrichten. Aus seinen Ereignissen entstehen die Zeilen.
        SseTestClient observer = new SseTestClient(httpClient, baseUrl, room);
        SseTestClient firstClient = new SseTestClient(httpClient, baseUrl, room);
        SseTestClient reconnected = null;
        try {
            postMessage(baseUrl, room, "sender-1", "sender-1-0");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(firstClient.eventCount()).isEqualTo(1));
            String lastEventId = firstClient.events.get(0).id;
            firstClient.close();

            for (int i = 0; i < MISSED_MESSAGES; i++) {
                postMessage(baseUrl, room, "sender-2", "sender-2-" + i);
            }
            await().atMost(Duration.ofSeconds(30)).untilAsserted(
                    () -> assertThat(observer.eventCount()).isEqualTo(MISSED_MESSAGES + 1));

            if (storeRows) {
                JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
                storeInDatabase(jdbcTemplate, observer.events);
                Integer stored = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM message WHERE room_id = ?", Integer.class, room);
                assertThat(stored).isEqualTo(MISSED_MESSAGES + 1);
            }

            // Die Unterbrechung dauert 10 Sekunden (Szenario S7). Das ist eine feste Pause des Szenarios, kein Warten auf eine Bedingung.
            await().pollDelay(PAUSE_WHILE_DISCONNECTED).atMost(PAUSE_WHILE_DISCONNECTED.plusSeconds(5)).until(() -> true);

            reconnected = new SseTestClient(httpClient, baseUrl, room, UUID.fromString(lastEventId));
            SseTestClient reconnectedClient = reconnected;
            await().atMost(Duration.ofSeconds(30)).untilAsserted(
                    () -> assertThat(reconnectedClient.eventCount()).isEqualTo(MISSED_MESSAGES));

            // Danach funktioniert die Live-Zustellung. Die Nachricht laeuft hinter allen nachgeholten durch
            // dieselbe Leitung: Kaemen Doppelte, waeren sie vor ihr da.
            postMessage(baseUrl, room, "sender-3", "sender-3-0");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(
                    () -> assertThat(reconnectedClient.eventCount()).isEqualTo(MISSED_MESSAGES + 1));

            Set<String> seenIds = new HashSet<>();
            for (int i = 0; i < MISSED_MESSAGES; i++) {
                SseTestClient.ReceivedEvent event = reconnectedClient.events.get(i);
                assertThat(seenIds.add(event.id)).as("id %s nur einmal", event.id).isTrue();
                assertThat(event.id).as("die erste Nachricht kommt nicht nochmals").isNotEqualTo(lastEventId);
                assertThat(objectMapper.readTree(event.data).get("content").asText()).isEqualTo("sender-2-" + i);
            }
            SseTestClient.ReceivedEvent live = reconnectedClient.events.get(MISSED_MESSAGES);
            assertThat(seenIds.add(live.id)).isTrue();
            assertThat(objectMapper.readTree(live.data).get("content").asText()).isEqualTo("sender-3-0");
            assertThat(reconnectedClient.formatViolations).isEmpty();
        } finally {
            observer.close();
            firstClient.close();
            if (reconnected != null) {
                reconnected.close();
            }
        }
    }

    // Variante 1: Die Nachrichten stehen in PostgreSQL. Der Ringbuffer fasst nur 10, der Rest kommt aus der Datenbank.
    @Test
    void reconnect_messagesInDatabase_deliversExactlyTheMissedMessages() throws Exception {
        ConfigurableApplicationContext context = startInstance(10);
        try {
            runScenario(context, true);
        } finally {
            context.close();
        }
    }

    // Variante 2: Die Nachrichten liegen nur im Ringbuffer, die Datenbank ist leer.
    @Test
    void reconnect_messagesOnlyInRingBuffer_deliversExactlyTheMissedMessages() throws Exception {
        ConfigurableApplicationContext context = startInstance(5000);
        try {
            runScenario(context, false);
        } finally {
            context.close();
        }
    }
}
