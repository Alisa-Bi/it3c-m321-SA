package ch.m321.chatapp.batchwriter.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht, wie sie auf chat.persist ankommt (siehe
 * docs/spec-batch-writer.md, Abschnitt 2). chat-service hat id und sentAt
 * bereits gesetzt - batch-writer erzeugt hier nichts mehr selbst.
 */
public record IncomingMessage(UUID id, UUID roomId, String senderId, String senderName, String content, Instant sentAt) {
}
