package ch.m321.chatapp.batchwriter.persistence;

import ch.m321.chatapp.batchwriter.messaging.PendingMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

/**
 * Schreibt einen Stapel Nachrichten in einer einzigen Transaktion nach
 * PostgreSQL. Bewusst JdbcTemplate statt JPA (PLANUNG.md 2.1) -
 * batchUpdate ist genau das Mittel, das diese Spezifikation vorsieht.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MessageWriter {

    private static final String UPSERT_SQL =
            "INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    // @Transactional sorgt dafuer, dass der ganze Stapel eine einzige
    // Datenbank-Transaktion ist (wichtig fuer S4: hoechstens 100
    // Transaktionen fuer 1000 Nachrichten). Ein Duplikat (gleiche id) wird
    // von ON CONFLICT DO NOTHING stillschweigend verworfen, nicht als
    // Fehler behandelt (siehe docs/spec-batch-writer.md, Abschnitt 5.2).
    @Transactional
    public void writeBatch(List<PendingMessage> batch) {
        jdbcTemplate.batchUpdate(UPSERT_SQL, batch, batch.size(), (statement, pendingMessage) -> {
            var message = pendingMessage.message();
            statement.setObject(1, message.id());
            statement.setObject(2, message.roomId());
            statement.setString(3, message.senderId());
            statement.setString(4, message.senderName());
            statement.setString(5, message.content());
            statement.setTimestamp(6, Timestamp.from(message.sentAt()));
        });
        log.info("Stapel von {} Nachrichten gespeichert", batch.size());
    }
}
