package ch.m321.chatapp.chat.replay;

import ch.m321.chatapp.chat.delivery.BufferedMessage;
import ch.m321.chatapp.chat.delivery.SseConnection;
import ch.m321.chatapp.chat.delivery.SseConnectionRegistry;
import ch.m321.chatapp.chat.history.MessagePosition;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Oeffnet eine SSE-Verbindung mit Last-Event-ID und fuehrt Nachholen und Live-Betrieb ohne Luecke
 * zusammen (docs/spec-live-zustellung.md, Abschnitt 5.3).
 *
 * Ablauf:
 * 1. Die Verbindung wird in der Registry angemeldet, im Nachhol-Modus. Eintreffende Live-Nachrichten
 *    werden fuer sie zwischengespeichert.
 * 2. Der Cursor wird geprueft. Scheitert das, wird die Verbindung abgemeldet und der Fehler (400, 409
 *    oder 503) geht als HTTP-Status an den Client. Nur bei Erfolg wird der Emitter zurueckgegeben,
 *    und erst dann sendet Spring HTTP 200.
 * 3. Auf einem eigenen Thread: Schnappschuss des Ringbuffers und Datenbankabfrage.
 * 4. bis 6. Die verpassten Nachrichten senden, dann die zwischengespeicherten Live-Nachrichten (ohne
 *    schon gesendete ids), dann in den Live-Modus wechseln (SseConnection.completeReplay).
 *
 * Warum keine Luecke entsteht: Schritt 1 liegt vor dem Schnappschuss in Schritt 3, und der Verteiler
 * traegt jede Nachricht zuerst in den Ringbuffer ein und verteilt sie erst danach (LiveDispatcher).
 * Eine Nachricht, die der Schnappschuss nicht mehr enthaelt, wurde also noch nicht verteilt, als
 * der Schnappschuss entstand. Sie wird danach verteilt, und weil die Verbindung dann schon angemeldet
 * ist, landet sie im Zwischenspeicher. Eine Nachricht kann so hoechstens in beiden Quellen stehen (Schnappschuss und
 * Zwischenspeicher), nie in keiner. Doppelte entfernt der Vergleich der ids beim Senden.
 *
 * Faellt die Datenbank in Schritt 3 aus, ist HTTP 200 schon gesendet. Dann wird die Verbindung
 * beendet, und der Client verbindet sich mit Last-Event-ID neu (Spezifikation, Abschnitt 6.6).
 */
@Component
@Slf4j
public class ReplayHandover {

    // Anzahl der Threads, die gleichzeitig nachholen. Weitere Verbindungen warten in der Warteschlange.
    private static final int REPLAY_THREADS = 16;

    private final SseConnectionRegistry registry;
    private final ReplayService replayService;
    private final ExecutorService replayExecutor;

    // registry: die offenen Verbindungen. replayService: bestimmt Cursor und verpasste Nachrichten.
    public ReplayHandover(SseConnectionRegistry registry, ReplayService replayService) {
        this.registry = registry;
        this.replayService = replayService;
        this.replayExecutor = Executors.newFixedThreadPool(REPLAY_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "sse-replay");
            thread.setDaemon(true);
            return thread;
        });
    }

    // Stoppt die Nachhol-Threads beim Herunterfahren der Anwendung.
    @PreDestroy
    void stopReplayThreads() {
        replayExecutor.shutdownNow();
    }

    // Schritte 1 und 2: anmelden und Cursor pruefen. Wirft die Ausnahme des ReplayService (400, 409, 503),
    // nachdem die Verbindung wieder abgemeldet ist. Bei Erfolg laeuft das Nachholen im Hintergrund weiter.
    public SseEmitter open(UUID roomId, UUID lastEventId) {
        SseConnection connection = registry.registerReplaying(roomId);
        MessagePosition cursor;
        try {
            cursor = replayService.resolveCursor(roomId, lastEventId);
        } catch (RuntimeException exception) {
            registry.remove(roomId, connection);
            throw exception;
        }

        try {
            connection.sendComment("connected");
            replayExecutor.execute(() -> replay(connection, cursor));
        } catch (IOException | IllegalStateException | RejectedExecutionException exception) {
            registry.removeAndClose(roomId, connection, exception);
        }
        log.info("SSE connection with Last-Event-ID {} opened for room {}", lastEventId, roomId);
        return connection.emitter();
    }

    // Schritte 3 bis 6 auf einem eigenen Thread. Jeder Fehler beendet nur diese Verbindung.
    private void replay(SseConnection connection, MessagePosition cursor) {
        UUID roomId = connection.roomId();
        try {
            List<BufferedMessage> missed = replayService.findMissed(roomId, cursor);
            connection.completeReplay(missed);
        } catch (ReplayUnavailableException exception) {
            log.warn("Replay for room {} failed, database unavailable, closing connection", roomId);
            registry.removeAndClose(roomId, connection, exception);
        } catch (IOException | IllegalStateException exception) {
            log.info("Replay for room {} ended, connection is gone: {}", roomId, exception.getMessage());
            registry.removeAndClose(roomId, connection, exception);
        } catch (RuntimeException exception) {
            log.error("Replay for room {} failed unexpectedly, closing connection", roomId, exception);
            registry.removeAndClose(roomId, connection, exception);
        }
    }
}
