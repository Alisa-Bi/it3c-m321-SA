package ch.m321.chatapp.chat.history;

import ch.m321.chatapp.chat.delivery.SseConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Szenario S5 auf Ebene des Dienstes (docs/spec-live-zustellung.md, Abschnitte 4.4 und 8):
 * Die Datenbank ist nicht erreichbar (Port 1, Verbindung wird sofort abgelehnt). Der Dienst
 * startet trotzdem, stellt eine Live-Nachricht zu, und eine Abfrage an die Datenbank
 * scheitert mit einer DataAccessException innerhalb des Zeitlimits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DatabaseUnavailableIntegrationTest {

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    // RabbitMQ aus dem Testcontainer, PostgreSQL absichtlich unerreichbar.
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:1/chat");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SseConnectionRegistry registry;

    @Autowired
    private MessageHistoryRepository repository;

    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    // Der Dienst ist ohne Datenbank gestartet: Eine Live-Nachricht erreicht den Client.
    @Test
    void databaseDown_liveMessageIsStillDelivered() throws Exception {
        UUID roomId = UUID.randomUUID();
        HttpRequest streamRequest = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/stream?roomId=" + roomId)).GET().build();
        HttpResponse<InputStream> stream = httpClient.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);

        AtomicBoolean received = new AtomicBoolean(false);
        Thread reader = new Thread(() -> readUntilData(stream.body(), received), "test-sse-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(registry.connectionCount(roomId)).isEqualTo(1));

            String json = "{\"roomId\":\"" + roomId + "\",\"senderId\":\"sender-1\","
                    + "\"senderName\":\"Test\",\"content\":\"ohne-datenbank\"}";
            HttpRequest post = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(post, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(202);

            await().atMost(Duration.ofSeconds(10)).untilTrue(received);
        } finally {
            stream.body().close();
        }
    }

    // Eine Abfrage an die nicht erreichbare Datenbank scheitert mit DataAccessException,
    // und zwar innerhalb des Zeitlimits (hier: Verbindungs-Timeout 1 s).
    @Test
    void databaseDown_queryFailsWithDataAccessExceptionInTime() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> repository.findPosition(UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMillis).isLessThan(10_000);
    }

    // Mit Last-Event-ID und ohne Datenbank antwortet /stream mit 503, und die Verbindung wird abgemeldet.
    @Test
    void streamWithLastEventId_whenDatabaseDown_answers503() throws Exception {
        UUID roomId = UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/stream?roomId=" + roomId))
                .header("Last-Event-ID", UUID.randomUUID().toString())
                .GET()
                .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(registry.connectionCount(roomId)).isZero();
    }

    // Liest Zeilen, bis eine data:-Zeile mit dem erwarteten Inhalt kommt, und setzt dann das Flag.
    private void readUntilData(InputStream body, AtomicBoolean received) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        try {
            String line = reader.readLine();
            while (line != null) {
                if (line.startsWith("data:") && line.contains("ohne-datenbank")) {
                    received.set(true);
                    return;
                }
                line = reader.readLine();
            }
        } catch (IOException e) {
            // Verbindung vom Test geschlossen, das Ende ist gewollt.
        }
    }
}
