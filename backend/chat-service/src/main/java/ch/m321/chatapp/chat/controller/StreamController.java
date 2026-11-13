package ch.m321.chatapp.chat.controller;

import ch.m321.chatapp.chat.delivery.SseConnection;
import ch.m321.chatapp.chat.delivery.SseConnectionRegistry;
import ch.m321.chatapp.chat.replay.ReplayHandover;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/**
 * Live-Endpunkt: GET /stream?roomId=... (docs/spec-live-zustellung.md, Abschnitt 2.2).
 *
 * Der Client erhaelt einen Server-Sent-Events-Strom. Fehlt roomId oder ist sie keine gueltige UUID,
 * antwortet der Endpunkt mit 400, ebenso bei einer ungueltigen Last-Event-ID.
 *
 * Ohne Last-Event-ID laeuft die Verbindung direkt im Live-Modus. Mit dem Header holt der Server zuerst
 * die verpassten Nachrichten nach (ReplayHandover). Die Antwort ist dann 400 (Cursor gehoert zu einem
 * anderen Raum), 409 (Cursor nicht mehr verfuegbar) oder 503 (Datenbank nicht erreichbar), bevor HTTP 200 gesendet wird.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class StreamController {

    private final SseConnectionRegistry registry;
    private final ReplayHandover replayHandover;

    // Oeffnet eine SSE-Verbindung fuer den Raum. Mit Last-Event-ID wird nachgeholt, sonst startet sie live.
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "roomId", required = false) String roomIdText,
                             @RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdText) {
        UUID roomId = parseUuid(roomIdText, "roomId");
        if (lastEventIdText == null || lastEventIdText.isBlank()) {
            return openLive(roomId);
        }
        UUID lastEventId = parseUuid(lastEventIdText.trim(), "Last-Event-ID");
        return replayHandover.open(roomId, lastEventId);
    }

    // Oeffnet eine Verbindung im Live-Modus und traegt sie in die Registry ein.
    private SseEmitter openLive(UUID roomId) {
        SseConnection connection = registry.register(roomId);
        try {
            // Ein erster Kommentar schickt die Antwort-Header sofort los, damit der Client
            // nicht bis zum ersten Heartbeat auf den Verbindungsaufbau warten muss.
            connection.sendComment("connected");
        } catch (IOException e) {
            registry.removeAndClose(roomId, connection, e);
        }
        log.info("SSE connection opened for room {}", roomId);
        return connection.emitter();
    }

    // Wandelt einen Wert in eine UUID um. Fehlt er oder ist er ungueltig, gibt es HTTP 400.
    private UUID parseUuid(String text, String name) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be a valid UUID");
        }
    }
}
