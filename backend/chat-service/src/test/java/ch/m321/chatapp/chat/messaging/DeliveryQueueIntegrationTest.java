package ch.m321.chatapp.chat.messaging;

import ch.m321.chatapp.chat.delivery.LiveDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Prueft mit einem echten RabbitMQ die Zustell-Queue dieser Instanz
 * (docs/spec-live-zustellung.md, Abschnitte 3.2 und 4.2):
 * Eine Nachricht an chat.delivery erreicht den LiveDispatcher, genau eine
 * Queue mit genau einem Consumer haengt am Exchange, und ein Fehler im
 * Dispatcher fuehrt nicht zu einer erneuten Zustellung.
 */
@SpringBootTest
@Testcontainers
class DeliveryQueueIntegrationTest {

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

    // Ersetzt den echten Dispatcher durch einen Mock, damit der Test sieht,
    // was bei ihm ankommt und ob ein Fehler dort Folgen hat.
    @MockBean
    private LiveDispatcher liveDispatcher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // Sendet einen JSON-Text als rohe Nachricht (ohne Umwandlung) an den Exchange.
    private void sendRawJson(String json) {
        Message message = MessageBuilder
                .withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .build();
        rabbitTemplate.send(RabbitMqConfig.DELIVERY_EXCHANGE, "", message);
    }

    // Fuehrt rabbitmqctl im Container aus und gibt die Ausgabe als Text zurueck.
    private String runRabbitmqctl(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "rabbitmqctl";
        for (int i = 0; i < arguments.length; i++) {
            command[i + 1] = arguments[i];
        }
        ExecResult result = rabbitmq.execInContainer(command);
        return result.getStdout();
    }

    // Eine Nachricht an chat.delivery muss unveraendert beim Dispatcher ankommen.
    @Test
    void messageToDeliveryExchange_reachesDispatcherUnchanged() {
        String json = "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"content\":\"Hallo\"}";

        sendRawJson(json);

        verify(liveDispatcher, timeout(5000)).dispatch(json);
    }

    // Am Exchange haengt genau eine Queue; sie hat genau einen Consumer und die
    // Eigenschaften nicht dauerhaft, auto-delete und exklusiv.
    @Test
    void deliveryExchange_hasExactlyOneQueueWithOneConsumer() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String bindings = runRabbitmqctl("list_bindings", "source_name", "destination_name");
            String queueName = null;
            int bindingCount = 0;
            String[] bindingLines = bindings.split("\n");
            for (String line : bindingLines) {
                String[] columns = line.split("\t");
                if (columns.length == 2 && columns[0].equals(RabbitMqConfig.DELIVERY_EXCHANGE)) {
                    queueName = columns[1].trim();
                    bindingCount++;
                }
            }
            assertThat(bindingCount).isEqualTo(1);

            String queues = runRabbitmqctl("list_queues", "name", "durable", "auto_delete", "exclusive", "consumers");
            String foundLine = null;
            String[] queueLines = queues.split("\n");
            for (String line : queueLines) {
                if (line.startsWith(queueName + "\t")) {
                    foundLine = line;
                }
            }
            assertThat(foundLine).isNotNull();
            String[] fields = foundLine.trim().split("\t");
            assertThat(fields[1]).isEqualTo("false");
            assertThat(fields[2]).isEqualTo("true");
            assertThat(fields[3]).isEqualTo("true");
            assertThat(fields[4]).isEqualTo("1");
        });
    }

    // Wirft der Dispatcher einen Fehler, darf die Nachricht nicht erneut
    // zugestellt werden: Er wird genau einmal aufgerufen, auch nach einer Wartezeit.
    @Test
    void dispatcherFailure_doesNotCauseRedelivery() {
        doThrow(new RuntimeException("test failure")).when(liveDispatcher).dispatch(anyString());

        sendRawJson("{\"id\":\"22222222-2222-2222-2222-222222222222\",\"content\":\"Fehler\"}");

        verify(liveDispatcher, after(3000).times(1)).dispatch(anyString());
    }
}
