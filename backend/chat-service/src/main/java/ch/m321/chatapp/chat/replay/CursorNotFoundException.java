package ch.m321.chatapp.chat.replay;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Die Last-Event-ID steht weder in der Datenbank noch im Ringbuffer des Raums. Der Server kann
 * dann nicht sicher bestimmen, was fehlt. Antwort: HTTP 409 (Spezifikation, Abschnitt 6.5).
 */
public class CursorNotFoundException extends ResponseStatusException {

    // Der Client muss ohne Last-Event-ID neu verbinden und seinen Verlauf anderweitig laden.
    public CursorNotFoundException() {
        super(HttpStatus.CONFLICT, "Last-Event-ID ist nicht mehr verfuegbar");
    }
}
