package ch.m321.chatapp.chat.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Nimmt jede Nachricht entgegen, die diese Instanz ueber ihre eigene Queue am
 * Exchange chat.delivery erhaelt (siehe docs/spec-live-zustellung.md,
 * Abschnitt 4.2).
 *
 * In diesem Schritt protokolliert die Klasse die Nachricht nur. Spaeter
 * verteilt sie die Nachricht an die offenen SSE-Verbindungen des passenden
 * Raums.
 */
@Component
@Slf4j
public class LiveDispatcher {

    // Bekommt den JSON-Text der Nachricht genau so, wie er aus der Queue kam.
    // Der Text wird bewusst nicht in ein Objekt umgewandelt, damit er spaeter
    // unveraendert an die Clients gesendet werden kann.
    public void dispatch(String messageJson) {
        log.info("Live message received: {}", messageJson);
    }
}
