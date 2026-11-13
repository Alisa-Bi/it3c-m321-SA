package ch.m321.chatapp.chat.messaging;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Zusaetzliche Pruefung S9 (docs/spec-live-zustellung.md, Abschnitt 8): Ein
 * Inhalt ueber der Grenze CHAT_MAX_CONTENT_BYTES (Standard 4096 UTF-8-Bytes)
 * wird mit HTTP 400 abgelehnt und weder an chat.persist noch an
 * chat.delivery veroeffentlicht. Ein Inhalt genau an der Grenze wird
 * angenommen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MessageSizeLimitIntegrationTest {

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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // Legt chat.persist und eine an chat.delivery gebundene Test-Queue an und
    // leert beide, damit jeder Test bei null beginnt.
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

    // Baut den JSON-Body einer Anfrage mit dem angegebenen Inhalt.
    private String requestWithContent(String content) {
        return "{\"roomId\":\"" + UUID.randomUUID() + "\",\"senderId\":\"user-1\","
                + "\"senderName\":\"Alice\",\"content\":\"" + content + "\"}";
    }

    // 4097 Bytes sind ein Byte zu viel: HTTP 400, und in keiner der beiden
    // Queues darf etwas ankommen.
    @Test
    void contentOverLimit_isRejectedAndNotPublished() throws Exception {
        String tooLong = "a".repeat(4097);

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestWithContent(tooLong)))
                .andExpect(status().isBadRequest());

        Message inPersistQueue = rabbitTemplate.receive(RabbitMqConfig.PERSIST_QUEUE, 1000);
        Message inDeliveryQueue = rabbitTemplate.receive(DELIVERY_COPY_QUEUE, 1000);
        assertThat(inPersistQueue).isNull();
        assertThat(inDeliveryQueue).isNull();
    }

    // Die Grenze zaehlt Bytes, nicht Zeichen: 2049 mal "ae" (Umlaut, je 2 Bytes
    // in UTF-8) sind 4098 Bytes, obwohl es nur 2049 Zeichen sind.
    @Test
    void contentOverLimitInBytesButNotInCharacters_isRejected() throws Exception {
        String umlauts = "ä".repeat(2049);

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestWithContent(umlauts)))
                .andExpect(status().isBadRequest());
    }

    // Genau 4096 Bytes sind erlaubt: HTTP 202, und die Nachricht kommt an.
    // Dieser Test stellt sicher, dass der 400-Test oben nicht einfach immer besteht.
    @Test
    void contentExactlyAtLimit_isAccepted() throws Exception {
        String exactlyAtLimit = "a".repeat(4096);

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestWithContent(exactlyAtLimit)))
                .andExpect(status().isAccepted());

        Message inPersistQueue = rabbitTemplate.receive(RabbitMqConfig.PERSIST_QUEUE, 5000);
        assertThat(inPersistQueue).isNotNull();
    }
}
