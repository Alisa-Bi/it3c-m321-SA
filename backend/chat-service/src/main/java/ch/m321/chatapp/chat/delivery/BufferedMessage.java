package ch.m321.chatapp.chat.delivery;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht im Ringbuffer: id, Raum, Zeitstempel und der JSON-Text genau so, wie er aus
 * der Queue kam. Der JSON-Text wird beim Nachholen unveraendert gesendet, damit Live-Zustellung
 * und Nachholen dasselbe Format haben (docs/spec-live-zustellung.md, Abschnitt 2.1).
 */
public record BufferedMessage(UUID id, UUID roomId, Instant sentAt, String json) {
}
