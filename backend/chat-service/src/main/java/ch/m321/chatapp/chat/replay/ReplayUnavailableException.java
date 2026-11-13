package ch.m321.chatapp.chat.replay;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Die Datenbank ist fuer das Nachholen nicht erreichbar oder zu langsam. Antwort: HTTP 503
 * (Spezifikation, Abschnitt 6.6). Die Ursache bleibt als cause erhalten, geht aber nicht an den Client.
 */
public class ReplayUnavailableException extends ResponseStatusException {

    // cause: der urspruengliche Datenbankfehler.
    public ReplayUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Nachholen ist zurzeit nicht moeglich", cause);
    }
}
