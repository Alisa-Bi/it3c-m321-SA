package ch.m321.chatapp.chat.repository;

import ch.m321.chatapp.chat.entity.Message;
import ch.m321.chatapp.chat.entity.MessageStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft gegen ein echtes PostgreSQL (Testcontainers), dass das Schema aus
 * Message.java tatsaechlich wie beabsichtigt entsteht: der UNIQUE-Constraint
 * auf messageId verhindert Duplikate, der zusammengesetzte Index auf
 * (room_id, created_at) existiert. Grundlage fuer die spaetere
 * Idempotenz-Pruefung im batch-writer (siehe docs/spec-batch-writer.md,
 * Abschnitt 4 und 5).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class MessageSchemaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // Verbindet den Testkontext mit dem gerade gestarteten Testcontainer,
    // statt mit der in application.yml eingetragenen Docker-Compose-Datenbank.
    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private EntityManager entityManager;

    // Eine einzelne, korrekt befuellte Nachricht muss weiterhin ganz normal
    // gespeichert werden koennen - der neue Constraint darf den Normalfall
    // nicht blockieren.
    @Test
    void savingSingleMessage_succeeds() {
        Message saved = messageRepository.save(newMessage(UUID.randomUUID()));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getMessageId()).isNotNull();
    }

    // Zwei Nachrichten mit identischem messageId duerfen nicht beide
    // gespeichert werden koennen - der UNIQUE-Constraint muss das auf
    // Datenbankebene verhindern, nicht nur die Anwendungslogik.
    @Test
    void savingSameMessageIdTwice_violatesUniqueConstraint() {
        UUID sharedMessageId = UUID.randomUUID();
        messageRepository.saveAndFlush(newMessage(sharedMessageId));

        assertThatThrownBy(() -> messageRepository.saveAndFlush(newMessage(sharedMessageId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Prueft, dass der in Message.java deklarierte zusammengesetzte Index
    // auf (room_id, created_at) tatsaechlich in der Datenbank existiert,
    // nicht nur als Java-Annotation im Code steht.
    @Test
    void compositeIndexOnRoomIdAndCreatedAt_exists() {
        List<?> matchingIndexes = entityManager.createNativeQuery(
                        "select indexname from pg_indexes "
                                + "where tablename = 'messages' "
                                + "and indexname = 'idx_messages_room_created_at'")
                .getResultList();

        assertThat(matchingIndexes).hasSize(1);
    }

    private Message newMessage(UUID messageId) {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setRoomId(UUID.randomUUID());
        message.setSenderId(UUID.randomUUID());
        message.setContent("Hallo");
        message.setCreatedAt(Instant.now());
        message.setStatus(MessageStatus.SENT);
        return message;
    }
}
