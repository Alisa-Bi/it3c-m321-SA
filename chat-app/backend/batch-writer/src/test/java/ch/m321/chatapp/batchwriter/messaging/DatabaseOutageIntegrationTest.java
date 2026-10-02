package ch.m321.chatapp.batchwriter.messaging;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduziert S7: Postgres ist 15 Sekunden nicht erreichbar, waehrenddessen
 * werden Nachrichten gesendet, danach ist Postgres wieder da. Erwartet:
 * binnen 90 Sekunden sind alle Nachrichten gespeichert, keine landet in
 * chat.dlq, batch-writer laeuft ohne Neustart durch (siehe
 * docs/spec-batch-writer.md, Abschnitt 5.4, Rechnung in Abschnitt 3).
 *
 * Nutzt bewusst die echten, in application.yml konfigurierten Werte
 * (15 s Retry-Verzoegerung, 3 Versuche) statt verkuerzter Testwerte, damit
 * dieser Test wirklich die Konfiguration prueft, die auch beim Lehrer-
 * Testlauf gilt - dadurch dauert er ca. 15-20 Sekunden.
 *
 * Verwendet eine kleinere Nachrichtenanzahl (20 statt 300) als im
 * tatsaechlichen Szenario, um die Testlaufzeit nicht unnoetig zu
 * verlaengern - der Mechanismus (Retry/Backoff ueber die Queue-Topologie)
 * ist unabhaengig von der Nachrichtenanzahl derselbe.
 */
@SpringBootTest
@Testcontainers
class DatabaseOutageIntegrationTest {

    private static final int MESSAGE_COUNT = 20;

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

    @Test
    void messagesSentDuringOutage_areEventuallyStored() throws Exception {
        List<UUID> messageIds = new ArrayList<>();

        DockerClient dockerClient = postgres.getDockerClient();
        dockerClient.pauseContainerCmd(postgres.getContainerId()).exec();

        try {
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                UUID messageId = UUID.randomUUID();
                messageIds.add(messageId);
                IncomingMessage message = new IncomingMessage(
                        messageId, UUID.randomUUID(), "user-1", "Alice", "Nachricht " + i, Instant.now());
                rabbitTemplate.convertAndSend(RabbitMqConfig.PERSIST_QUEUE, message);
            }

            Thread.sleep(15_000);
        } finally {
            dockerClient.unpauseContainerCmd(postgres.getContainerId()).exec();
        }

        int storedCount = waitForAllStored(messageIds.size());
        assertThat(storedCount).isEqualTo(MESSAGE_COUNT);

        Long dlqDepth = rabbitTemplate.execute(channel ->
                (long) channel.messageCount(RabbitMqConfig.DLQ));
        assertThat(dlqDepth).isZero();
    }

    // Wartet bis zu 90 Sekunden (S7-Grenze), alle 2 Sekunden geprueft, bis
    // alle gesendeten Nachrichten in der Tabelle stehen. Ein einfaches
    // COUNT(*) genuegt, weil dieser Test auf einer eigenen, zu Testbeginn
    // leeren Testcontainer-Datenbank laeuft - anders als beim Lehrer-
    // Testlauf auf dem echten Stack gibt es hier keine Vorbelegung aus
    // anderen Szenarien.
    private int waitForAllStored(int expectedCount) throws InterruptedException {
        int storedCount = 0;
        for (int attempt = 0; attempt < 45; attempt++) {
            Integer currentCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM message", Integer.class);
            storedCount = currentCount != null ? currentCount : 0;
            if (storedCount == expectedCount) {
                return storedCount;
            }
            Thread.sleep(2_000);
        }
        return storedCount;
    }
}
