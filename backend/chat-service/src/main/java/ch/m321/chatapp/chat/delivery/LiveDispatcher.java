package ch.m321.chatapp.chat.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Verteilt jede Nachricht, die diese Instanz ueber ihre eigene Queue am Exchange
 * chat.delivery erhaelt, an die offenen SSE-Verbindungen des passenden Raums
 * (docs/spec-live-zustellung.md, Abschnitte 2.3 und 4).
 *
 * Reihenfolge: Die Queue dieser Instanz hat genau einen Consumer mit genau einem
 * Thread (DeliveryListener, concurrency = 1). Alle Nachrichten laufen deshalb
 * nacheinander durch diese Klasse. Das haelt die Reihenfolge je Absender ein: Was
 * ein Absender nacheinander sendet, erreicht den Client in derselben Reihenfolge.
 * Wer diese Klasse parallel aufruft, bricht diese Garantie.
 *
 * Der JSON-Text wird nicht neu serialisiert, sondern genau so weitergegeben, wie er
 * aus der Queue kam. Nur id und roomId werden ausgelesen.
 *
 * Ein langsamer Client haelt den einen Verteil-Thread auf, solange sein Schreiben
 * blockiert. Dagegen schuetzt die Laengengrenze der Queue (Schritt 7).
 */
@Component
@Slf4j
public class LiveDispatcher {

    private final SseConnectionRegistry registry;
    private final ObjectMapper objectMapper;

    // registry: die offenen Verbindungen. objectMapper: nur zum Auslesen von id und roomId.
    public LiveDispatcher(SseConnectionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    // Sendet die Nachricht als SSE-Ereignis an alle Verbindungen ihres Raums.
    // Eine Nachricht ohne lesbare id oder roomId wird verworfen und protokolliert.
    public void dispatch(String messageJson) {
        UUID roomId;
        String messageId;
        try {
            JsonNode root = objectMapper.readTree(messageJson);
            messageId = readText(root, "id");
            roomId = UUID.fromString(readText(root, "roomId"));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Live message dropped, id or roomId unreadable: {}", messageJson);
            return;
        }

        List<SseEmitter> connections = registry.connectionsOf(roomId);
        for (SseEmitter emitter : connections) {
            sendToConnection(roomId, emitter, messageId, messageJson);
        }
        log.debug("Message {} delivered to {} connection(s) of room {}", messageId, connections.size(), roomId);
    }

    // Schickt das Ereignis an eine Verbindung. Schlaegt das fehl, wird nur diese Verbindung
    // beendet und ausgetragen; die anderen Verbindungen sind davon nicht betroffen.
    private void sendToConnection(UUID roomId, SseEmitter emitter, String messageId, String messageJson) {
        try {
            emitter.send(SseEmitter.event().id(messageId).data(messageJson));
        } catch (IOException | IllegalStateException e) {
            log.info("Write to SSE connection of room {} failed, removing it", roomId);
            registry.removeAndClose(roomId, emitter, e);
        }
    }

    // Liest ein Textfeld aus dem JSON. Fehlt es, wird eine IllegalArgumentException geworfen.
    private String readText(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || !field.isTextual()) {
            throw new IllegalArgumentException("Field missing or not text: " + fieldName);
        }
        return field.asText();
    }
}
