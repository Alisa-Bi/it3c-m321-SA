package ch.m321.chatapp.chat.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Vertrag einer Chatnachricht - zugleich REST-Eingabe (als Teilmenge) und
 * vollstaendige Nutzlast auf der Queue chat.persist.
 *
 * id und sentAt setzt der Client NICHT - chat-service vergibt beide beim
 * Publizieren (siehe PLANUNG.md 3.4: "UUID vergeben, Server-Zeitstempel
 * setzen"), bevor die Nachricht an RabbitMQ geht. id ist zugleich der
 * Idempotenz-Schluessel fuer batch-writer (siehe docs/spec-batch-writer.md,
 * Abschnitt 2 und 5.2) - es gibt bewusst kein zweites, separates Feld dafuer.
 */
public record NewMessageRequest(UUID id, UUID roomId, String senderId, String senderName, String content, Instant sentAt) {
}
