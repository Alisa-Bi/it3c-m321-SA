package ch.m321.chatapp.chat.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Prueft mit einem echten RabbitMQ und echten HTTP-Verbindungen die raumgenaue
 * Verteilung (docs/spec-live-zustellung.md, Abschnitte 2.3 und 4):
 * Zwei Clients in Raum A und einer in Raum B. 100 nummerierte Nachrichten eines
 * Absenders gehen an Raum A. Beide Clients in A erhalten alle 100 in der
 * gesendeten Reihenfolge, der Client in B keine. Eine anschliessende Nachricht
 * an Raum B erreicht nur den Client in B. Zeilenformat: id:, data:, Leerzeile.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RoomDeliveryIntegrationTest {

    private static final int MESSAGE_COUNT = 100;

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    // Verbindet die Anwendung mit dem Testcontainer statt mit dem Host "rabbitmq".
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SseConnectionRegistry registry;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** Ein empfangenes SSE-Ereignis mit den rohen Zeilen. */
    private static final class ReceivedEvent {
        private final String id;
        private final String data;

        ReceivedEvent(String id, String data) {
            this.id = id;
            this.data = data;
        }
    }

    /**
     * Ein SSE-Testclient: oeffnet GET /stream und liest in einem eigenen Thread Zeile fuer Zeile.
     * Ein Ereignis ist fertig, wenn die Leerzeile kommt. Kommentarzeilen (Heartbeat) werden gezaehlt,
     * aber nicht als Ereignis behandelt. Verstoesst eine Zeile gegen das Format, wird sie gemerkt.
     */
    private final class SseTestClient {
        private final List<ReceivedEvent> events = new CopyOnWriteArrayList<>();
        private final List<String> formatViolations = new CopyOnWriteArrayList<>();
        private final InputStream body;
        private final Thread readerThread;

        // Oeffnet die Verbindung und startet den Lese-Thread.
        SseTestClient(UUID roomId) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/stream?roomId=" + roomId)).GET().build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            this.body = response.body();
            this.readerThread = new Thread(this::readLoop, "test-sse-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        // Liest Zeilen und setzt daraus Ereignisse zusammen.
        private void readLoop() {
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            String id = null;
            String data = null;
            try {
                String line = reader.readLine();
                while (line != null) {
                    if (line.startsWith(":")) {
                        // Kommentarzeile (connected oder heartbeat), kein Ereignis.
                    } else if (line.startsWith("id:")) {
                        id = line.substring(3);
                    } else if (line.startsWith("data:")) {
                        data = line.substring(5);
                    } else if (line.isEmpty()) {
                        if (id != null && data != null) {
                            events.add(new ReceivedEvent(id, data));
                        } else if (id != null || data != null) {
                            formatViolations.add("Ereignis nur mit id oder nur mit data (id=" + id + ")");
                        }
                        // Eine Leerzeile ohne id und data beendet nur einen Kommentarblock (connected, heartbeat).
                        id = null;
                        data = null;
                    } else {
                        formatViolations.add("Unerwartete Zeile: " + line);
                    }
                    line = reader.readLine();
                }
            } catch (IOException e) {
                // Verbindung vom Test geschlossen, das Ende ist gewollt.
            }
        }

        // Anzahl der bisher vollstaendig empfangenen Ereignisse.
        int eventCount() {
            return events.size();
        }

        // Schliesst die Verbindung.
        void close() throws IOException {
            body.close();
        }
    }

    // Sendet eine Nachricht per POST /messages und prueft, dass sie angenommen wurde.
    private void postMessage(UUID roomId, String senderId, String content) throws Exception {
        String json = "{\"roomId\":\"" + roomId + "\",\"senderId\":\"" + senderId
                + "\",\"senderName\":\"Test\",\"content\":\"" + content + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
    }

    // Prueft die Reihenfolge und das Format der Ereignisse eines Clients in Raum A.
    private void assertAllMessagesInOrder(SseTestClient client, UUID roomId) throws Exception {
        assertThat(client.formatViolations).isEmpty();
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            ReceivedEvent event = client.events.get(i);
            JsonNode json = objectMapper.readTree(event.data);
            assertThat(json.get("content").asText()).isEqualTo("msg-" + i);
            assertThat(json.get("roomId").asText()).isEqualTo(roomId.toString());
            assertThat(json.get("id").asText()).isEqualTo(event.id);
            assertThat(seenIds.add(event.id)).as("id %s nur einmal", event.id).isTrue();
        }
    }

    // Beide Clients in Raum A erhalten alle 100 Nachrichten in Reihenfolge, der Client in Raum B keine.
    @Test
    void messagesReachOnlyTheirRoom_inSenderOrder() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        List<SseTestClient> clients = new ArrayList<>();
        try {
            SseTestClient firstInA = new SseTestClient(roomA);
            clients.add(firstInA);
            SseTestClient secondInA = new SseTestClient(roomA);
            clients.add(secondInA);
            SseTestClient inB = new SseTestClient(roomB);
            clients.add(inB);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(registry.connectionCount(roomA)).isEqualTo(2);
                assertThat(registry.connectionCount(roomB)).isEqualTo(1);
            });

            for (int i = 0; i < MESSAGE_COUNT; i++) {
                postMessage(roomA, "user-1", "msg-" + i);
            }

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(firstInA.eventCount()).isEqualTo(MESSAGE_COUNT);
                assertThat(secondInA.eventCount()).isEqualTo(MESSAGE_COUNT);
            });
            assertAllMessagesInOrder(firstInA, roomA);
            assertAllMessagesInOrder(secondInA, roomA);
            assertThat(inB.eventCount()).as("Client in Raum B bekam nichts aus Raum A").isZero();

            // Gegenprobe: Eine Nachricht an Raum B erreicht nur Raum B.
            postMessage(roomB, "user-2", "only-for-b");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inB.eventCount()).isEqualTo(1));
            assertThat(firstInA.eventCount()).isEqualTo(MESSAGE_COUNT);
            assertThat(secondInA.eventCount()).isEqualTo(MESSAGE_COUNT);
            assertThat(inB.formatViolations).isEmpty();
        } finally {
            for (SseTestClient client : clients) {
                client.close();
            }
        }
    }

    // Faellt eine Verbindung aus, erhalten die anderen im Raum trotzdem weiter alle Nachrichten.
    @Test
    void failingConnection_doesNotDisturbOthers() throws Exception {
        UUID room = UUID.randomUUID();
        SseTestClient staying = new SseTestClient(room);
        SseTestClient leaving = new SseTestClient(room);
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(registry.connectionCount(room)).isEqualTo(2));
            leaving.close();

            for (int i = 0; i < 20; i++) {
                postMessage(room, "user-1", "msg-" + i);
            }

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(staying.eventCount()).isEqualTo(20));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(registry.connectionCount(room)).isEqualTo(1));
        } finally {
            staying.close();
        }
    }
}
