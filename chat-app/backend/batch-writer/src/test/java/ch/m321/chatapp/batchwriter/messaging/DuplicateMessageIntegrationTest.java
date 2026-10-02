package ch.m321.chatapp.batchwriter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduziert S5 exakt: dieselbe Nachricht (gleiche id) wird zweimal
 * direkt in chat.persist gelegt, nur mit dem Header
 * content_type: application/json gesetzt. Erwartet: genau eine Zeile,
 * nichts in chat.dlq (siehe docs/spec-batch-writer.md, Abschnitt 5.2
 * und 6).
 */
@SpringBootTest
@Testcontainers
class DuplicateMessageIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-test.sql");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // Veroeffentlicht die Nachricht manuell mit ausschliesslich dem
    // content_type-Header, ohne weitere, vom Standard-Converter sonst
    // zusaetzlich gesetzte Header (z. B. __TypeId__) - exakt wie in S5
    // beschrieben ("nur mit dem Header content_type: application/json").
    @Test
    void sameMessageTwice_resultsInExactlyOneRow() throws Exception {
        UUID messageId = UUID.randomUUID();
        IncomingMessage incomingMessage = new IncomingMessage(
                messageId, UUID.randomUUID(), "user-1", "Alice", "Hallo zweimal", Instant.now());
        byte[] body = objectMapper.writeValueAsBytes(incomingMessage);

        publishRaw(body);
        publishRaw(body);

        int rowCount = waitForRowCount(messageId);
        assertThat(rowCount).isEqualTo(1);

        Long dlqDepth = rabbitTemplate.execute(channel ->
                (long) channel.messageCount(RabbitMqConfig.DLQ));
        assertThat(dlqDepth).isZero();
    }

    private void publishRaw(byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        Message rawMessage = new Message(body, properties);
        rabbitTemplate.send(RabbitMqConfig.PERSIST_QUEUE, rawMessage);
    }

    // Wartet bis zu 5 Sekunden (alle 200 ms geprueft) auf den naechsten
    // zeitbasierten Flush des BatchBuffer, statt eine feste Wartezeit zu
    // verwenden, die entweder zu kurz oder unnoetig lang waere.
    private int waitForRowCount(UUID messageId) throws InterruptedException {
        int rowCount = 0;
        for (int attempt = 0; attempt < 25; attempt++) {
            Integer currentCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM message WHERE id = ?", Integer.class, messageId);
            rowCount = currentCount != null ? currentCount : 0;
            if (rowCount > 0) {
                return rowCount;
            }
            Thread.sleep(200);
        }
        return rowCount;
    }
}
