package ch.m321.chatapp.chat.messaging;

import ch.m321.chatapp.chat.delivery.LiveDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Prueft mit einem echten RabbitMQ die Laengengrenze der Zustell-Queue
 * (docs/spec-live-zustellung.md, Abschnitt 3.3 und Szenario S6):
 * Haengt der Verbraucher, halten Queue und Consumer zusammen hoechstens
 * 1000 Nachrichten (990 bereit, 10 unbestaetigt), und nach dem Weiterlaufen
 * werden neue Nachrichten wieder zugestellt. Ausserdem: Queue-Argumente und
 * der angeforderte RabbitMQ-Heartbeat.
 *
 * Der Verbraucher "haengt", indem der Dispatcher im Test blockiert. Den
 * Listener einfach anzuhalten ginge nicht: Die Queue ist auto-delete und wuerde
 * mit dem letzten Consumer geloescht. Eine blockierte Instanz (wie bei
 * docker pause) behaelt ihre Verbindung und ihren Consumer.
 */
@SpringBootTest
@Testcontainers
class QueueLimitIntegrationTest {

    private static final int SENT_MESSAGES = 2000;

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

    // Ersetzt den echten Dispatcher, damit der Test ihn blockieren kann.
    @MockBean
    private LiveDispatcher liveDispatcher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private CachingConnectionFactory connectionFactory;

    // Sendet einen JSON-Text als rohe Nachricht an den Exchange chat.delivery.
    private void sendRawJson(String json) {
        Message message = MessageBuilder
                .withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .build();
        rabbitTemplate.send(RabbitMqConfig.DELIVERY_EXCHANGE, "", message);
    }

    // Fuehrt rabbitmqctl list_queues im Container aus und gibt die Zeile der Zustell-Queue zurueck
    // (Spalten durch Tabulator getrennt), oder null, wenn sie noch nicht da ist.
    private String[] deliveryQueueRow(String... columns) throws Exception {
        String[] command = new String[columns.length + 3];
        command[0] = "rabbitmqctl";
        command[1] = "list_queues";
        command[2] = "name";
        for (int i = 0; i < columns.length; i++) {
            command[i + 3] = columns[i];
        }
        String output = rabbitmq.execInContainer(command).getStdout();
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.startsWith("spring.gen-")) {
                return line.trim().split("\t");
            }
        }
        return null;
    }

    // Haengt der Verbraucher, bleiben hoechstens 990 Nachrichten bereit und 10 unbestaetigt (zusammen 1000).
    // Laeuft er wieder, werden neue Nachrichten zugestellt.
    @Test
    void stalledConsumer_queueStaysWithinLimit_andDeliveryResumes() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        doAnswer(invocation -> {
            gate.await();
            return null;
        }).when(liveDispatcher).dispatch(anyString());

        for (int i = 0; i < SENT_MESSAGES; i++) {
            sendRawJson("{\"id\":\"" + UUID.randomUUID() + "\",\"roomId\":\"" + UUID.randomUUID()
                    + "\",\"content\":\"stau-" + i + "\"}");
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String[] row = deliveryQueueRow("messages", "messages_ready", "messages_unacknowledged");
            assertThat(row).isNotNull();
            int total = Integer.parseInt(row[1]);
            int ready = Integer.parseInt(row[2]);
            int unacknowledged = Integer.parseInt(row[3]);
            assertThat(ready).as("bereitliegende Nachrichten").isEqualTo(990);
            assertThat(unacknowledged).as("unbestaetigte Nachrichten (Prefetch)").isEqualTo(10);
            assertThat(total).as("Nachrichten insgesamt").isLessThanOrEqualTo(1000);
        });

        // Der Verbraucher laeuft wieder: Der Rueckstau wird abgearbeitet, und eine neue Nachricht kommt an.
        gate.countDown();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String[] row = deliveryQueueRow("messages");
            assertThat(row).isNotNull();
            assertThat(Integer.parseInt(row[1])).isZero();
        });
        String newMessage = "{\"id\":\"" + UUID.randomUUID() + "\",\"roomId\":\"" + UUID.randomUUID()
                + "\",\"content\":\"nach-dem-stau\"}";
        sendRawJson(newMessage);
        verify(liveDispatcher, timeout(10000)).dispatch(newMessage);
    }

    // Die Queue ist mit x-max-length 990 und x-overflow drop-head angelegt.
    @Test
    void deliveryQueue_hasMaxLengthAndDropHeadArguments() throws Exception {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String[] row = deliveryQueueRow("arguments");
            assertThat(row).isNotNull();
            assertThat(row[1]).contains("x-max-length").contains("990");
            assertThat(row[1]).contains("x-overflow").contains("drop-head");
        });
    }

    // Der angeforderte RabbitMQ-Heartbeat betraegt 30 Sekunden.
    @Test
    void requestedHeartbeat_is30Seconds() {
        assertThat(connectionFactory.getRabbitConnectionFactory().getRequestedHeartbeat()).isEqualTo(30);
    }
}
