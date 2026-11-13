package ch.m321.chatapp.chat.history;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft den lesenden Zugriff mit einem echten PostgreSQL (Testcontainers). Das Schema
 * stammt aus init-test.sql, einer Kopie von infrastructure/postgres/init.sql. Eine
 * eingefuegte Zeile wird mit Raum und Mikrosekunden-Zeitstempel gelesen, eine unbekannte
 * id ergibt kein Ergebnis. RabbitMQ wird nicht gebraucht (Listener ausgeschaltet).
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Testcontainers
class MessageHistoryRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withInitScript("init-test.sql");

    // Verbindet die Anwendung mit dem Testcontainer statt mit dem Host "postgres".
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MessageHistoryRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fuegt eine Zeile ein, so wie es der batch-writer taete.
    private void insertMessage(UUID id, UUID roomId, Instant sentAt) {
        jdbcTemplate.update(
                "INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, roomId, "sender-1", "Test", "Hallo", OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC));
    }

    // Eine eingefuegte Zeile wird mit Raum und unverlustigem Mikrosekunden-Zeitstempel gelesen.
    @Test
    void findPosition_returnsRoomAndMicrosecondTimestamp() {
        UUID id = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant sentAt = Instant.parse("2026-01-02T03:04:05.123456Z");
        insertMessage(id, roomId, sentAt);

        Optional<MessagePosition> position = repository.findPosition(id);

        assertThat(position).isPresent();
        assertThat(position.get().id()).isEqualTo(id);
        assertThat(position.get().roomId()).isEqualTo(roomId);
        assertThat(position.get().sentAt()).isEqualTo(sentAt);
    }

    // Eine id, die nicht gespeichert ist, ergibt kein Ergebnis und keinen Fehler.
    @Test
    void findPosition_unknownId_returnsEmpty() {
        assertThat(repository.findPosition(UUID.randomUUID())).isEmpty();
    }
}
