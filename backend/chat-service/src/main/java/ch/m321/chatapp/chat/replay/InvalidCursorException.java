package ch.m321.chatapp.chat.replay;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Die Last-Event-ID gehoert zu einer gespeicherten Nachricht eines anderen Raums.
 * Antwort: HTTP 400 (docs/spec-live-zustellung.md, Abschnitt 6.4).
 */
public class InvalidCursorException extends ResponseStatusException {

    // Der Text nennt keine Raum-ids, er geht an den Client.
    public InvalidCursorException() {
        super(HttpStatus.BAD_REQUEST, "Last-Event-ID gehoert nicht zu diesem Raum");
    }
}
