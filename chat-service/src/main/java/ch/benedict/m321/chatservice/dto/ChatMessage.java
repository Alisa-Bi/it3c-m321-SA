package ch.benedict.m321.chatservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Die fertige Nachricht, wie sie in beide Queues geht.
 *
 * Diese Form ist der Vertrag zwischen den Diensten — aber der Vertrag ist
 * das JSON, nicht diese Klasse. batch-writer und web-gateway bekommen
 * später ihre eigene Kopie. Ein gemeinsames Modul würde alle Dienste
 * aneinanderbinden, und genau das wollen wir nicht zeigen.
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
