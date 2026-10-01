package ch.benedict.m321.chatservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Was der chat-service zurückgibt, nachdem er die Nachricht angenommen hat.
 *
 * Der Client braucht die id, um seine eigene Nachricht in der Zustellung
 * wiederzuerkennen, und den Zeitstempel, um sie richtig einzusortieren.
 *
 * @param id     die vom Server vergebene Nachrichten-ID
 * @param sentAt der vom Server gesetzte Zeitpunkt
 */
public record AcceptedResponse(UUID id, Instant sentAt) {
}
