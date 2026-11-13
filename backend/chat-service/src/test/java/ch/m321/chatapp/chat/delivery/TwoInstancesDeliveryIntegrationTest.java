package ch.m321.chatapp.chat.delivery;

import ch.m321.chatapp.chat.ChatServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Szenario S3 als Test (docs/spec-live-zustellung.md, Abschnitt 8): Zwei
 * getrennte Instanzen des chat-service teilen sich ein echtes RabbitMQ. An
 * jeder Instanz haengt ein Client auf Raum A, ein weiterer auf Raum B.
 * Ueber jede Instanz gehen 200 Nachrichten an Raum A, mit je eigener senderId.
 * Beide Clients in A muessen alle 400 Nachrichten genau einmal erhalten, je
 * Absender in der gesendeten Reihenfolge. Der Client in B erhaelt keine.
 */
@Testcontainers
class TwoInstancesDeliveryIntegrationTest {

    private static final int MESSAGES_PER_INSTANCE = 200;

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    private static ConfigurableApplicationContext instance1;
    private static ConfigurableApplicationContext instance2;

    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Startet die beiden Instanzen, jede mit eigenem zufaelligem Port und eigener Queue.
    @BeforeAll
    static void startInstances() {
        instance1 = startInstance();
        instance2 = startInstance();
    }

    // Beendet beide Instanzen.
    @AfterAll
    static void stopInstances() {
        if (instance2 != null) {
            instance2.close();
        }
        if (instance1 != null) {
            instance1.close();
        }
    }

    // Startet eine Instanz des chat-service gegen den Testcontainer.
    private static ConfigurableApplicationContext startInstance() {
        return new SpringApplicationBuilder(ChatServiceApplication.class).run(
                "--server.port=0",
                "--spring.rabbitmq.host=" + rabbitmq.getHost(),
                "--spring.rabbitmq.port=" + rabbitmq.getAmqpPort(),
                "--spring.rabbitmq.username=" + rabbitmq.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbitmq.getAdminPassword());
    }

    // Adresse einer Instanz, aus dem zufaelligen Port.
    private static String baseUrl(ConfigurableApplicationContext context) {
        return "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    // Zaehlt die Bindungen am Exchange chat.delivery (je Instanz eine).
    private int deliveryBindingCount() throws Exception {
        String output = rabbitmq.execInContainer("rabbitmqctl", "list_bindings", "source_name", "destination_name").getStdout();
        int count = 0;
        for (String line : output.split("\n")) {
            if (line.startsWith("chat.delivery")) {
                count++;
            }
        }
        return count;
    }

    // Sendet nacheinander count Nachrichten ueber eine Instanz. Inhalt: "<senderId>-<Nummer>".
    private void postMessages(String baseUrl, UUID roomId, String senderId, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            postMessage(baseUrl, roomId, senderId, senderId + "-" + i);
        }
    }

    // Sendet eine einzelne Nachricht und prueft, dass sie angenommen wurde (202).
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

    // Prueft bei einem Client: genau 400 Ereignisse, keine doppelte id, und je Absender
    // kommen die Nummern lueckenlos in der gesendeten Reihenfolge (0, 1, 2, ...).
    private void assertExactlyOnceAndInSenderOrder(SseTestClient client) throws Exception {
        assertThat(client.formatViolations).isEmpty();
        assertThat(client.eventCount()).isEqualTo(2 * MESSAGES_PER_INSTANCE);
        Set<String> seenIds = new HashSet<>();
        Map<String, Integer> nextExpected = new HashMap<>();
        for (SseTestClient.ReceivedEvent event : client.events) {
            assertThat(seenIds.add(event.id)).as("id %s nur einmal", event.id).isTrue();
            JsonNode json = objectMapper.readTree(event.data);
            String senderId = json.get("senderId").asText();
            String content = json.get("content").asText();
            int number = Integer.parseInt(content.substring(senderId.length() + 1));
            int expected = nextExpected.getOrDefault(senderId, 0);
            assertThat(number).as("Reihenfolge von %s", senderId).isEqualTo(expected);
            nextExpected.put(senderId, expected + 1);
        }
        assertThat(nextExpected.get("sender-1")).isEqualTo(MESSAGES_PER_INSTANCE);
        assertThat(nextExpected.get("sender-2")).isEqualTo(MESSAGES_PER_INSTANCE);
    }

    // Beide Clients in Raum A erhalten alle 400 Nachrichten genau einmal und in Absenderreihenfolge,
    // der Client in Raum B bekommt keine.
    @Test
    void twoInstances_deliverEveryMessageOnceInSenderOrder() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        String url1 = baseUrl(instance1);
        String url2 = baseUrl(instance2);
        SseConnectionRegistry registry1 = instance1.getBean(SseConnectionRegistry.class);
        SseConnectionRegistry registry2 = instance2.getBean(SseConnectionRegistry.class);

        assertThat(url1).isNotEqualTo(url2);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(deliveryBindingCount()).isEqualTo(2));

        SseTestClient clientA1 = new SseTestClient(httpClient, url1, roomA);
        SseTestClient clientA2 = new SseTestClient(httpClient, url2, roomA);
        SseTestClient clientB = new SseTestClient(httpClient, url1, roomB);
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(registry1.connectionCount(roomA)).isEqualTo(1);
                assertThat(registry2.connectionCount(roomA)).isEqualTo(1);
                assertThat(registry1.connectionCount(roomB)).isEqualTo(1);
            });

            // Beide Instanzen senden gleichzeitig, jede mit eigener senderId.
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread sender2 = new Thread(() -> {
                try {
                    postMessages(url2, roomA, "sender-2", MESSAGES_PER_INSTANCE);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-sender-2");
            sender2.start();
            postMessages(url1, roomA, "sender-1", MESSAGES_PER_INSTANCE);
            sender2.join(60_000);
            assertThat(failure.get()).as("Fehler beim Senden ueber Instanz 2").isNull();

            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertThat(clientA1.eventCount()).isEqualTo(2 * MESSAGES_PER_INSTANCE);
                assertThat(clientA2.eventCount()).isEqualTo(2 * MESSAGES_PER_INSTANCE);
            });

            // Abschlussmarke an Raum B: Sie laeuft hinter allen Nachrichten durch dieselben Queues.
            // Ist sie angekommen, sind eventuelle Doppelte in A laengst da und wuerden die Zahl erhoehen.
            postMessage(url2, roomB, "sender-3", "marker-for-b");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(clientB.eventCount()).isEqualTo(1));

            assertExactlyOnceAndInSenderOrder(clientA1);
            assertExactlyOnceAndInSenderOrder(clientA2);
            assertThat(clientB.events.get(0).data).contains("marker-for-b");
            assertThat(clientB.formatViolations).isEmpty();
        } finally {
            clientA1.close();
            clientA2.close();
            clientB.close();
        }
    }
}
