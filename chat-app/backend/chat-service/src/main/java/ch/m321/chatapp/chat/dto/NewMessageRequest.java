package ch.m321.chatapp.chat.dto;

import java.util.UUID;

/**
 * Was ein Client schickt, um eine neue Nachricht zu versenden.
 *
 * messageId wird vom REST-Client NICHT gesetzt - chat-service erzeugt sie
 * selbst beim Publizieren (siehe MessageService.publishNewMessage()), damit
 * der spaetere batch-writer jede Nachricht eindeutig als "schon verarbeitet"
 * erkennen kann (Idempotenz, siehe docs/spec-batch-writer.md, Abschnitt 2).
 * Enthaelt bewusst noch keine id/createdAt/status - diese werden erst beim
 * tatsaechlichen Speichern vergeben (siehe MessageService.createMessage).
 * Dieses Objekt ist zugleich die REST-Anfrage (POST /api/messages) und die
 * Nutzlast, die anschliessend ueber RabbitMQ transportiert wird - fuer ein
 * einzelnes Objekt mit zwei Verwendungszwecken braucht es keine zwei
 * fast identischen Klassen.
 */
public record NewMessageRequest(UUID messageId, UUID roomId, UUID senderId, String content) {
}
