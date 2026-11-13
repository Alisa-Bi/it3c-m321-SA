package ch.m321.chatapp.chat.delivery;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * aus der Queue kam. Nur id, roomId und sentAt werden ausgelesen.
 *
 * Puffer vor Verteilung: Jede Nachricht wird zuerst in den RecentMessageBuffer eingetragen
 * und erst danach an die Verbindungen gesendet. Ein Client, der sich waehrend des Nachholens
 * neu anmeldet, findet eine Nachricht so immer an mindestens einer Stelle: im Schnappschuss
 * des Puffers (sie wurde schon eingetragen) oder bei seiner Verbindung (sie wird erst danach
 * verteilt). Waere die Reihenfolge umgekehrt, koennte eine Nachricht an der alten
 * Verbindungsliste vorbeigehen und noch nicht im Puffer stehen, wenn der Client den Schnappschuss
 * liest. Dann ginge sie verloren (siehe Spezifikation, Abschnitt 5.3).
 *
 * Ein langsamer Client haelt den einen Verteil-Thread auf, solange sein Schreiben
 * blockiert. Dagegen schuetzt die Laengengrenze der Queue (Schritt 7).
 */
@Component
@Slf4j
public class LiveDispatcher {

    private final SseConnectionRegistry registry;
    private final ObjectMapper objectMapper;
    private final RecentMessageBuffer buffer;

    // registry: die offenen Verbindungen. objectMapper: nur zum Auslesen von id, roomId und sentAt.
    // buffer: der Ringbuffer, in den jede Nachricht vor der Verteilung eingetragen wird.
    public LiveDispatcher(SseConnectionRegistry registry, ObjectMapper objectMapper, RecentMessageBuffer buffer) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.buffer = buffer;
    }

    // Traegt die Nachricht in den Ringbuffer ein und sendet sie danach als SSE-Ereignis an alle
    // Verbindungen ihres Raums. Eine Nachricht ohne lesbare id oder roomId wird verworfen und
    // protokolliert. Ist nur sentAt nicht lesbar, wird sie zwar verteilt, aber nicht gepuffert.
    public void dispatch(String messageJson) {
        UUID roomId;
        String messageId;
        Instant sentAt;
        try {
            // Dezimalzahlen als BigDecimal lesen: sentAt hat Mikrosekunden, ein double wuerde sie verlieren.
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(messageJson);
            messageId = readText(root, "id");
            roomId = UUID.fromString(readText(root, "roomId"));
            sentAt = readSentAt(root);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Live message dropped, id or roomId unreadable: {}", messageJson);
            return;
        }

        // Zuerst puffern, dann verteilen. Begruendung steht im Kommentar der Klasse.
        if (sentAt == null) {
            log.warn("Live message {} not buffered, sentAt unreadable", messageId);
        } else {
            buffer.add(new BufferedMessage(UUID.fromString(messageId), roomId, sentAt, messageJson));
        }

        List<SseConnection> connections = registry.connectionsOf(roomId);
        for (SseConnection connection : connections) {
            sendToConnection(roomId, connection, messageId, messageJson);
        }
        log.debug("Message {} delivered to {} connection(s) of room {}", messageId, connections.size(), roomId);
    }

    // Gibt das Ereignis an eine Verbindung: im Live-Modus geht es sofort an den Client, im Nachhol-Modus
    // in den Zwischenspeicher der Verbindung. Schlaegt das fehl (Schreibfehler oder voller Zwischenspeicher),
    // wird nur diese Verbindung beendet und ausgetragen; die anderen Verbindungen sind davon nicht betroffen.
    private void sendToConnection(UUID roomId, SseConnection connection, String messageId, String messageJson) {
        try {
            connection.deliver(messageId, messageJson);
        } catch (IOException | IllegalStateException e) {
            log.info("Delivery to SSE connection of room {} failed, removing it: {}", roomId, e.getMessage());
            registry.removeAndClose(roomId, connection, e);
        }
    }

    // Liest sentAt: Sekunden seit 1970 als Zahl mit Nachkommastellen (Spezifikation, Abschnitt 2.1).
    // Das Ergebnis wird auf Mikrosekunden gekuerzt, wie in der Datenbank. Null, wenn das Feld fehlt
    // oder keine Zahl ist.
    private Instant readSentAt(JsonNode root) {
        JsonNode field = root.get("sentAt");
        if (field == null || !field.isNumber()) {
            return null;
        }
        BigDecimal value = field.decimalValue();
        long seconds = value.setScale(0, RoundingMode.FLOOR).longValueExact();
        long nanos = value.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).setScale(0, RoundingMode.DOWN).longValueExact();
        return Instant.ofEpochSecond(seconds, nanos).truncatedTo(ChronoUnit.MICROS);
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
