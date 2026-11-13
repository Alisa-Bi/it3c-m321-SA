package ch.m321.chatapp.chat.delivery;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Eine offene SSE-Verbindung eines Clients zu einem Raum.
 *
 * Die Verbindung hat zwei Zustaende. Im Live-Modus geht jede Nachricht sofort an den Client. Im
 * Nachhol-Modus (Client verbindet sich mit Last-Event-ID) werden eintreffende Live-Nachrichten
 * zwischengespeichert, bis die verpassten Nachrichten gesendet sind. Danach folgen die
 * zwischengespeicherten, und die Verbindung wechselt in den Live-Modus (docs/spec-live-zustellung.md,
 * Abschnitt 5.3). Der Zwischenspeicher ist auf replayBufferMax Nachrichten begrenzt. Wird die
 * Grenze ueberschritten, schlaegt deliver mit einer IOException fehl, der Aufrufer beendet die
 * Verbindung, und der Client verbindet sich neu.
 *
 * Schreibzugriffe: Live-Pfad (Verteil-Thread), Nachhol-Pfad (eigener Thread) und Heartbeat (eigener
 * Thread) schreiben auf denselben SseEmitter. Jedes Schreiben laeuft durch sendLock, so schreibt
 * immer nur ein Thread auf einmal.
 *
 * Zwei Sperren: stateLock schuetzt nur den Zustand (Modus und Zwischenspeicher) und wird nie
 * waehrend eines Netzwerkzugriffs gehalten. Sonst bliebe der eine Verteil-Thread, der alle Raeume
 * bedient, an einem langsamen Nachholen haengen. sendLock umschliesst nur das einzelne Schreiben.
 */
public class SseConnection {

    // Eine zwischengespeicherte Live-Nachricht: id und JSON-Text.
    private record PendingEvent(String id, String json) {
    }

    private final UUID roomId;
    private final SseEmitter emitter;
    private final int replayBufferMax;

    private final Object stateLock = new Object();
    private final Object sendLock = new Object();

    // Beides geschuetzt durch stateLock.
    private boolean replaying;
    private List<PendingEvent> pending = new ArrayList<>();

    // replaying: true, wenn die Verbindung im Nachhol-Modus startet. replayBufferMax: Grenze des
    // Zwischenspeichers (CHAT_SSE_REPLAY_BUFFER_MAX).
    SseConnection(UUID roomId, SseEmitter emitter, boolean replaying, int replayBufferMax) {
        this.roomId = roomId;
        this.emitter = emitter;
        this.replaying = replaying;
        this.replayBufferMax = replayBufferMax;
    }

    // Der Raum dieser Verbindung.
    public UUID roomId() {
        return roomId;
    }

    // Der SseEmitter, der an Spring zurueckgegeben wird.
    public SseEmitter emitter() {
        return emitter;
    }

    // Wird vom Verteiler fuer jede Live-Nachricht aufgerufen. Im Live-Modus geht sie sofort an den Client,
    // im Nachhol-Modus in den Zwischenspeicher. Wirft eine IOException, wenn das Schreiben scheitert oder
    // der Zwischenspeicher voll ist.
    public void deliver(String messageId, String json) throws IOException {
        synchronized (stateLock) {
            if (replaying) {
                if (pending.size() >= replayBufferMax) {
                    throw new IOException("Replay buffer limit of " + replayBufferMax + " messages reached");
                }
                pending.add(new PendingEvent(messageId, json));
                return;
            }
        }
        sendEvent(messageId, json);
    }

    // Schliesst das Nachholen ab: erst die verpassten Nachrichten senden, dann die zwischengespeicherten
    // Live-Nachrichten (ohne die, deren id schon gesendet wurde), danach in den Live-Modus wechseln.
    // Waehrend des Sendens kommen weiter Live-Nachrichten in den Zwischenspeicher. Der Wechsel in den
    // Live-Modus geschieht erst, wenn der Zwischenspeicher leer ist, und zwar unter stateLock.
    public void completeReplay(List<BufferedMessage> missed) throws IOException {
        Set<String> sentIds = new HashSet<>();
        for (BufferedMessage message : missed) {
            String messageId = message.id().toString();
            sendEvent(messageId, message.json());
            sentIds.add(messageId);
        }
        while (true) {
            List<PendingEvent> batch;
            synchronized (stateLock) {
                if (pending.isEmpty()) {
                    replaying = false;
                    return;
                }
                batch = pending;
                pending = new ArrayList<>();
            }
            for (PendingEvent event : batch) {
                if (sentIds.add(event.id())) {
                    sendEvent(event.id(), event.json());
                }
            }
        }
    }

    // Sendet eine SSE-Kommentarzeile (zum Beispiel connected oder heartbeat).
    public void sendComment(String text) throws IOException {
        synchronized (sendLock) {
            emitter.send(SseEmitter.event().comment(text));
        }
    }

    // Sendet ein SSE-Ereignis mit id und data.
    private void sendEvent(String messageId, String json) throws IOException {
        synchronized (sendLock) {
            emitter.send(SseEmitter.event().id(messageId).data(json));
        }
    }
}
