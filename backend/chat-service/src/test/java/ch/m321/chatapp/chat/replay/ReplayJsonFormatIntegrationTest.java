package ch.m321.chatapp.chat.replay;

import ch.m321.chatapp.chat.delivery.BufferedMessage;
import ch.m321.chatapp.chat.delivery.RecentMessageBuffer;
import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.history.MessageHistoryRepository;
import ch.m321.chatapp.chat.messaging.RabbitMqConfig;
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
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Szenario S11 (docs/spec-live-zustellung.md, Abschnitt 8): Eine Nachricht wird ueber POST /messages
 * veroeffentlicht und aus PostgreSQL wieder gelesen. Aus der Zeile entsteht ein NewMessageRequest, der
 * mit demselben Jackson-Converter serialisiert wird. Der JSON-Text der nachgeholten Nachricht muss
 * mit dem urspruenglich veroeffentlichten Text identisch sein. Der ReplayService hat dafuer einen leeren
 * Ringbuffer, die Nachricht kommt also sicher aus der Datenbank. Der Inhalt enthaelt Umlaute, Anfuehrungszeichen,
 * einen Backslash, HTML-Zeichen und ein Emoji.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReplayJsonFormatIntegrationTest {

    private static final String DELIVERY_COPY_QUEUE = "test.replay-format-copy";

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
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MessageHistoryRepository repository;

    @Autowired
    private Jackson2JsonMessageConverter jsonConverter;

    // Legt eine Test-Queue an, die an chat.delivery gebunden ist, und leert sie.
    @BeforeEach
    void prepareQueue() {
        FanoutExchange deliveryExchange = new FanoutExchange(RabbitMqConfig.DELIVERY_EXCHANGE, true, false);
        amqpAdmin.declareExchange(deliveryExchange);
        Queue copyQueue = new Queue(DELIVERY_COPY_QUEUE, false);
        amqpAdmin.declareQueue(copyQueue);
        amqpAdmin.declareBinding(BindingBuilder.bind(copyQueue).to(deliveryExchange));
        amqpAdmin.purgeQueue(DELIVERY_COPY_QUEUE, false);
    }

    // Veroeffentlichte Nachricht -> Zeile in PostgreSQL -> nachgeholte Nachricht: derselbe JSON-Text.
    @Test
    void replayedMessageFromDatabase_hasIdenticalJsonToThePublishedMessage() throws Exception {
        UUID roomId = UUID.randomUUID();
        String content = "Gr\u00fcezi \"Welt\" \\ <b>&amp;</b> \ud83d\ude00";
        String requestBody = objectMapper.writeValueAsString(new NewMessageRequest(null, roomId, "user-1", "Alice", content, null));

        mockMvc.perform(post("/messages").contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isAccepted());

        Message deliveryMessage = rabbitTemplate.receive(DELIVERY_COPY_QUEUE, 5000);
        assertThat(deliveryMessage).isNotNull();
        String publishedJson = new String(deliveryMessage.getBody(), StandardCharsets.UTF_8);
        NewMessageRequest published = objectMapper.readValue(publishedJson, NewMessageRequest.class);
        assertThat(published.content()).isEqualTo(content);

        // So wuerde der batch-writer die Nachricht speichern, dazu eine fruehere Nachricht als Cursor.
        UUID cursorId = UUID.randomUUID();
        insertRow(cursorId, roomId, published.sentAt().minusSeconds(1), "cursor");
        insertRow(published.id(), roomId, published.sentAt(), published.content());

        ReplayService service = new ReplayService(repository, new RecentMessageBuffer(10, 100), jsonConverter);
        List<BufferedMessage> missed = service.missedMessages(roomId, cursorId);

        assertThat(missed).hasSize(1);
        assertThat(missed.get(0).id()).isEqualTo(published.id());
        assertThat(missed.get(0).json()).isEqualTo(publishedJson);
    }

    // Fuegt eine Zeile in die Tabelle message ein.
    private void insertRow(UUID id, UUID roomId, Instant sentAt, String content) {
        jdbcTemplate.update(
                "INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, roomId, "user-1", "Alice", content, OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC));
    }
}
