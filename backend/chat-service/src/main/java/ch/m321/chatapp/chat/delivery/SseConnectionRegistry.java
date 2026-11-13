package ch.m321.chatapp.chat.delivery;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fuehrt die offenen SSE-Verbindungen dieser Instanz, gruppiert nach Raum
 * (docs/spec-live-zustellung.md, Abschnitte 2.2 bis 2.4 und 6).
 *
 * Die Klasse ist threadsicher: Verbindungen kommen und gehen auf
 * Tomcat-Threads, der Heartbeat laeuft auf einem eigenen Thread, und der
 * Verteiler liest vom Listener-Thread. Darum wird die Liste eines Raums nie
 * veraendert, sondern bei jeder Aenderung durch eine neue, unveraenderte
 * Kopie ersetzt (copy on write). Wer eine Liste liest, kann sie ohne Sperre
 * durchlaufen.
 *
 * Eine Verbindung wird entfernt, sobald sie abgeschlossen ist, in den
 * Timeout laeuft oder einen Fehler meldet. Es bleibt keine Referenz auf eine
 * beendete Verbindung zurueck.
 */
@Component
@Slf4j
public class SseConnectionRegistry {

    private final ConcurrentHashMap<UUID, List<SseEmitter>> connectionsByRoom = new ConcurrentHashMap<>();

    private final long timeoutMillis;
    private final Duration heartbeatInterval;
    private ScheduledExecutorService heartbeatExecutor;

    // timeoutMillis: Lebensdauer einer Verbindung (CHAT_SSE_TIMEOUT, Millisekunden).
    // heartbeatInterval: Abstand zwischen zwei Heartbeats (CHAT_SSE_HEARTBEAT_INTERVAL).
    public SseConnectionRegistry(@Value("${chat.sse.timeout}") long timeoutMillis,
                                 @Value("${chat.sse.heartbeat-interval}") Duration heartbeatInterval) {
        this.timeoutMillis = timeoutMillis;
        this.heartbeatInterval = heartbeatInterval;
    }

    // Startet den Heartbeat-Thread. Er laeuft als Daemon, damit er das Beenden der JVM nicht blockiert.
    @PostConstruct
    void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = heartbeatInterval.toMillis();
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    // Stoppt den Heartbeat-Thread beim Herunterfahren der Anwendung.
    @PreDestroy
    void stopHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }

    // Legt eine neue Verbindung fuer den Raum an und traegt sie ein.
    // Die Rueckrufe sorgen dafuer, dass sie bei Ende, Timeout oder Fehler wieder austragen wird.
    public SseEmitter register(UUID roomId) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        emitter.onCompletion(() -> remove(roomId, emitter));
        emitter.onTimeout(() -> remove(roomId, emitter));
        emitter.onError(error -> remove(roomId, emitter));
        connectionsByRoom.compute(roomId, (key, current) -> {
            List<SseEmitter> updated = new ArrayList<>();
            if (current != null) {
                updated.addAll(current);
            }
            updated.add(emitter);
            return List.copyOf(updated);
        });
        return emitter;
    }

    // Traegt eine Verbindung aus. Ist sie nicht (mehr) eingetragen, passiert nichts.
    // Ein Raum ohne Verbindungen wird ganz entfernt.
    public void remove(UUID roomId, SseEmitter emitter) {
        connectionsByRoom.computeIfPresent(roomId, (key, current) -> {
            List<SseEmitter> updated = new ArrayList<>();
            for (SseEmitter candidate : current) {
                if (candidate != emitter) {
                    updated.add(candidate);
                }
            }
            if (updated.isEmpty()) {
                return null;
            }
            return List.copyOf(updated);
        });
    }

    // Gibt die aktuellen Verbindungen des Raums zurueck (unveraenderliche Momentaufnahme).
    public List<SseEmitter> connectionsOf(UUID roomId) {
        List<SseEmitter> current = connectionsByRoom.get(roomId);
        if (current == null) {
            return List.of();
        }
        return current;
    }

    // Anzahl der offenen Verbindungen in einem Raum.
    public int connectionCount(UUID roomId) {
        return connectionsOf(roomId).size();
    }

    // Anzahl der offenen Verbindungen ueber alle Raeume.
    public int totalConnectionCount() {
        int total = 0;
        for (List<SseEmitter> list : connectionsByRoom.values()) {
            total += list.size();
        }
        return total;
    }

    // Schickt jeder offenen Verbindung eine SSE-Kommentarzeile. Schlaegt das Schreiben bei einer
    // Verbindung fehl, wird nur diese Verbindung beendet und ausgetragen.
    void sendHeartbeat() {
        try {
            for (java.util.Map.Entry<UUID, List<SseEmitter>> entry : connectionsByRoom.entrySet()) {
                UUID roomId = entry.getKey();
                for (SseEmitter emitter : entry.getValue()) {
                    try {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (IOException | IllegalStateException e) {
                        log.debug("Heartbeat failed, removing connection of room {}", roomId);
                        remove(roomId, emitter);
                        closeQuietly(emitter, e);
                    }
                }
            }
        } catch (RuntimeException e) {
            // Der geplante Task darf nie abbrechen, sonst laeuft kein Heartbeat mehr.
            log.warn("Unexpected error while sending heartbeat", e);
        }
    }

    // Beendet eine Verbindung mit Fehler und ignoriert Folgefehler beim Schliessen.
    private void closeQuietly(SseEmitter emitter, Exception cause) {
        try {
            emitter.completeWithError(cause);
        } catch (RuntimeException ignored) {
            // Die Verbindung ist schon beendet, mehr gibt es nicht zu tun.
        }
    }
}
