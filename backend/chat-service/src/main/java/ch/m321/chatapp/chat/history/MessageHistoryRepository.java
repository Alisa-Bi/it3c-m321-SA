package ch.m321.chatapp.chat.history;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    // Zeilenvergleich (sent_at, id) > Position: Bei gleichem Zeitstempel entscheidet die id. PostgreSQL
    // vergleicht uuid Byte fuer Byte; der ReplayService sortiert in Java mit derselben Reihenfolge.
    private static final String FIND_AFTER_SQL =
            "SELECT id, room_id, sender_id, sender_name, content, sent_at FROM message "
                    + "WHERE room_id = ? AND (sent_at, id) > (?::timestamptz, ?::uuid) "
                    + "ORDER BY sent_at, id";

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

    // Liest alle Nachrichten eines Raums, die nach der Position liegen, aufsteigend nach
    // (sent_at, id). Aus jeder Zeile entsteht ein NewMessageRequest, damit das JSON spaeter mit
    // demselben Converter erzeugt werden kann wie beim Veroeffentlichen.
    public List<NewMessageRequest> findAfter(UUID roomId, MessagePosition position) {
        return jdbcTemplate.query(
                FIND_AFTER_SQL,
                (resultSet, rowNumber) -> new NewMessageRequest(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("room_id", UUID.class),
                        resultSet.getString("sender_id"),
                        resultSet.getString("sender_name"),
                        resultSet.getString("content"),
                        resultSet.getObject("sent_at", OffsetDateTime.class).toInstant()),
                roomId,
                OffsetDateTime.ofInstant(position.sentAt(), ZoneOffset.UTC),
                position.id());
    }
}
