package ch.m321.chatapp.chat.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft mit einem echten RabbitMQ, dass POST /messages dieselbe Nachricht
 * auf beiden Wegen veroeffentlicht: in die Queue chat.persist und an den
 * Exchange chat.delivery. Beide JSON-Texte muessen identisch sein
 * (docs/spec-live-zustellung.md, Abschnitte 2.1 und 4.1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PublishBothWaysIntegrationTest {

    private static final String DELIVERY_COPY_QUEUE = "test.delivery-copy";

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    // Verbindet die Anwendung mit dem Testcontainer statt mit dem Host "rabbitmq".
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        // Dieser Test braucht den Zustell-Listener nicht und soll ihn nicht stoeren.
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // Legt die Queue chat.persist an (im Betrieb macht das der batch-writer)
    // und eine Test-Queue, die an chat.delivery gebunden ist. Beide werden
    // geleert, damit Tests sich nicht gegenseitig stoeren.
    @BeforeEach
    void prepareQueues() {
        FanoutExchange deliveryExchange = new FanoutExchange(RabbitMqConfig.DELIVERY_EXCHANGE, true, false);
        amqpAdmin.declareExchange(deliveryExchange);

        Queue persistQueue = new Queue(RabbitMqConfig.PERSIST_QUEUE, true);
        amqpAdmin.declareQueue(persistQueue);

        Queue deliveryCopyQueue = new Queue(DELIVERY_COPY_QUEUE, false);
        amqpAdmin.declareQueue(deliveryCopyQueue);
        Binding binding = BindingBuilder.bind(deliveryCopyQueue).to(deliveryExchange);
        amqpAdmin.declareBinding(binding);

        amqpAdmin.purgeQueue(RabbitMqConfig.PERSIST_QUEUE, false);
        amqpAdmin.purgeQueue(DELIVERY_COPY_QUEUE, false);
    }

    // Eine Nachricht senden und beide Kopien vergleichen. Zusaetzlich wird
    // festgehalten, dass sentAt als Zahl uebertragen wird (siehe Spezifikation,
    // Abschnitt 2.1).
    @Test
    void postMessage_publishesIdenticalJsonToPersistQueueAndDeliveryExchange() throws Exception {
        String requestBody = "{\"roomId\":\"" + UUID.randomUUID() + "\",\"senderId\":\"user-1\","
                + "\"senderName\":\"Alice\",\"content\":\"Hallo beide Wege\"}";

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted());

        Message persistMessage = rabbitTemplate.receive(RabbitMqConfig.PERSIST_QUEUE, 5000);
        Message deliveryMessage = rabbitTemplate.receive(DELIVERY_COPY_QUEUE, 5000);

        assertThat(persistMessage).isNotNull();
        assertThat(deliveryMessage).isNotNull();

        String persistJson = new String(persistMessage.getBody(), StandardCharsets.UTF_8);
        String deliveryJson = new String(deliveryMessage.getBody(), StandardCharsets.UTF_8);
        assertThat(deliveryJson).isEqualTo(persistJson);

        JsonNode parsed = objectMapper.readTree(persistJson);
        assertThat(parsed.get("sentAt").isNumber()).isTrue();
        assertThat(parsed.get("content").asText()).isEqualTo("Hallo beide Wege");
    }
}
