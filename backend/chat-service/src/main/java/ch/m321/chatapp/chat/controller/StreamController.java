package ch.m321.chatapp.chat.controller;

import ch.m321.chatapp.chat.delivery.SseConnectionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/**
 * Live-Endpunkt: GET /stream?roomId=... (docs/spec-live-zustellung.md, Abschnitt 2.2).
 *
 * Der Client erhaelt einen Server-Sent-Events-Strom. Fehlt roomId oder ist sie
 * keine gueltige UUID, antwortet der Endpunkt mit 400. Der Header Last-Event-ID
 * wird in diesem Schritt noch nicht ausgewertet (kommt mit dem Nachholen).
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class StreamController {

    private final SseConnectionRegistry registry;

    // Oeffnet eine SSE-Verbindung fuer den Raum und traegt sie in die Registry ein.
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "roomId", required = false) String roomIdText) {
        UUID roomId = parseRoomId(roomIdText);
        SseEmitter emitter = registry.register(roomId);
        try {
            // Ein erster Kommentar schickt die Antwort-Header sofort los, damit der Client
            // nicht bis zum ersten Heartbeat auf den Verbindungsaufbau warten muss.
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            registry.remove(roomId, emitter);
            emitter.completeWithError(e);
        }
        log.info("SSE connection opened for room {}", roomId);
        return emitter;
    }

    // Wandelt den Parameter in eine UUID um. Fehlt er oder ist er ungueltig, gibt es HTTP 400.
    private UUID parseRoomId(String roomIdText) {
        if (roomIdText == null || roomIdText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId is required");
        }
        try {
            return UUID.fromString(roomIdText);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId must be a valid UUID");
        }
    }
}
