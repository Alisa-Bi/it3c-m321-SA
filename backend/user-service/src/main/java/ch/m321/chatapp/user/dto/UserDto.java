package ch.m321.chatapp.user.dto;

import java.util.UUID;

/**
 * Oeffentliche Sicht auf einen Benutzer, wie sie ueber die API ausgeliefert
 * wird. Enthaelt bewusst keine internen Felder wie die Keycloak-ID - die
 * geht niemanden ausserhalb dieses Service etwas an.
 */
public record UserDto(UUID id, String username, String displayName) {
}
