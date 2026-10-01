package ch.m321.chatapp.chat.dto;

import java.util.UUID;

/**
 * Was ein Client schickt, um eine neue Nachricht zu versenden.
 *
 * Enthaelt bewusst noch keine id/createdAt/status - diese werden erst beim
 * tatsaechlichen Speichern vergeben (siehe MessageService.createMessage).
 * Dieses Objekt ist zugleich die REST-Anfrage (POST /api/messages) und die
 * Nutzlast, die anschliessend ueber RabbitMQ transportiert wird - fuer ein
 * einzelnes Objekt mit zwei Verwendungszwecken braucht es keine zwei
 * fast identischen Klassen.
 */
public record NewMessageRequest(UUID roomId, UUID senderId, String content) {
}
