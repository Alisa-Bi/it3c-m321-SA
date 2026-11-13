package ch.m321.chatapp.chat.history;

import java.time.Instant;
import java.util.UUID;

/**
 * Position einer gespeicherten Nachricht: Raum, Zeitstempel und id.
 *
 * Aus (sentAt, id) bildet das Nachholen spaeter seine Reihenfolge und den Cursor
 * (docs/spec-live-zustellung.md, Abschnitt 5). Der Zeitstempel hat Mikrosekunden,
 * genau wie in der Datenbank und in der Live-Nachricht.
 */
public record MessagePosition(UUID id, UUID roomId, Instant sentAt) {
}
