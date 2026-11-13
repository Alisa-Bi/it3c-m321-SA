package ch.m321.chatapp.chat.history;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lesender Zugriff auf die Tabelle message (infrastructure/postgres/init.sql).
 *
 * Die Tabelle wird hier nur gelesen, nie geaendert. Das Schreiben bleibt Aufgabe des
 * batch-writer. Ist die Datenbank nicht erreichbar oder zu langsam, wirft jede Methode
 * eine DataAccessException. Der Aufrufer entscheidet, was daraus wird (HTTP 503, siehe
 * docs/spec-live-zustellung.md, Abschnitt 6.6).
 */
@Repository
public class MessageHistoryRepository {

    private static final String FIND_POSITION_SQL = "SELECT room_id, sent_at FROM message WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    // jdbcTemplate: mit dem Zeitlimit fuer das Nachholen (MessageHistoryConfig).
    public MessageHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Liest Raum und Zeitstempel zu einer Nachrichten-id. Leer, wenn die Nachricht
    // (noch) nicht gespeichert ist.
    public Optional<MessagePosition> findPosition(UUID messageId) {
        List<MessagePosition> rows = jdbcTemplate.query(
                FIND_POSITION_SQL,
                (resultSet, rowNumber) -> new MessagePosition(
                        messageId,
                        resultSet.getObject("room_id", UUID.class),
                        resultSet.getObject("sent_at", OffsetDateTime.class).toInstant()),
                messageId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }
}
