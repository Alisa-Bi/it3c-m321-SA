package ch.m321.chatapp.chat.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ringbuffer der zuletzt empfangenen Nachrichten dieser Instanz, nach Raum geordnet
 * (docs/spec-live-zustellung.md, Abschnitt 5.4).
 *
 * Zweck: Der batch-writer schreibt in Stapeln. Eine Nachricht kann beim Nachholen noch nicht
 * in der Datenbank stehen. Der Puffer haelt sie bis dahin bereit.
 *
 * Grenzen: Pro Raum bleiben hoechstens maxPerRoom Nachrichten, in der ganzen Instanz hoechstens
 * maxTotal. Wird eine Grenze ueberschritten, faellt zuerst die aelteste Nachricht des Raums
 * (Grenze pro Raum), danach die insgesamt aelteste Nachricht (globale Grenze) heraus. Die
 * Grenze pro Raum gilt unabhaengig von der globalen. Aelteste heisst: zuerst eingetragen.
 *
 * Die Nachrichten eines Raums liegen in der Reihenfolge ihres Eintrags. Die Sortierung nach
 * (sentAt, id) macht erst das Nachholen.
 *
 * Der Puffer ist threadsicher: Der Verteil-Thread schreibt, HTTP-Threads lesen Schnappschuesse.
 * Jede Methode ist synchronisiert. Der Schnappschuss ist eine Kopie, spaetere Eintraege aendern
 * ihn nicht. Der Inhalt liegt nur im Arbeitsspeicher und geht beim Neustart verloren.
 */
@Component
public class RecentMessageBuffer {

    private final int maxPerRoom;
    private final int maxTotal;

    // Nachrichten je Raum, in Eintragsreihenfolge, mit schnellem Zugriff ueber die id.
    private final Map<UUID, LinkedHashMap<UUID, BufferedMessage>> rooms = new HashMap<>();

    // Alle Nachrichten der Instanz in Eintragsreihenfolge: Nachrichten-id -> Raum-id.
    private final LinkedHashMap<UUID, UUID> insertionOrder = new LinkedHashMap<>();

    // maxPerRoom: Grenze je Raum. maxTotal: Grenze je Instanz. Beide muessen mindestens 1 sein.
    public RecentMessageBuffer(@Value("${chat.ringbuffer.per-room}") int maxPerRoom,
                               @Value("${chat.ringbuffer.total}") int maxTotal) {
        if (maxPerRoom < 1 || maxTotal < 1) {
            throw new IllegalArgumentException("Ring buffer limits must be at least 1");
        }
        this.maxPerRoom = maxPerRoom;
        this.maxTotal = maxTotal;
    }

    // Traegt eine Nachricht ein und haelt danach beide Grenzen ein. Eine Nachricht mit bereits
    // bekannter id wird ignoriert, damit eine doppelt zugestellte Nachricht nicht doppelt liegt.
    public synchronized void add(BufferedMessage message) {
        if (insertionOrder.containsKey(message.id())) {
            return;
        }
        LinkedHashMap<UUID, BufferedMessage> room = rooms.computeIfAbsent(message.roomId(), key -> new LinkedHashMap<>());
        room.put(message.id(), message);
        insertionOrder.put(message.id(), message.roomId());

        if (room.size() > maxPerRoom) {
            removeOldestOfRoom(room);
        }
        if (insertionOrder.size() > maxTotal) {
            removeOldestOverall();
        }
    }

    // Sucht eine Nachricht im Puffer dieses Raums. Eine Nachricht eines anderen Raums wird nicht gefunden.
    public synchronized Optional<BufferedMessage> find(UUID roomId, UUID messageId) {
        LinkedHashMap<UUID, BufferedMessage> room = rooms.get(roomId);
        if (room == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(room.get(messageId));
    }

    // Kopie aller Nachrichten eines Raums in Eintragsreihenfolge. Leer, wenn der Raum unbekannt ist.
    public synchronized List<BufferedMessage> snapshot(UUID roomId) {
        LinkedHashMap<UUID, BufferedMessage> room = rooms.get(roomId);
        if (room == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(room.values());
    }

    // Anzahl aller Nachrichten in der Instanz.
    public synchronized int totalSize() {
        return insertionOrder.size();
    }

    // Anzahl der Nachrichten eines Raums.
    public synchronized int roomSize(UUID roomId) {
        LinkedHashMap<UUID, BufferedMessage> room = rooms.get(roomId);
        if (room == null) {
            return 0;
        }
        return room.size();
    }

    // Anzahl der Raeume, die noch Nachrichten haben.
    public synchronized int roomCount() {
        return rooms.size();
    }

    // Entfernt die aelteste Nachricht eines Raums (Grenze pro Raum).
    private void removeOldestOfRoom(LinkedHashMap<UUID, BufferedMessage> room) {
        Iterator<UUID> oldest = room.keySet().iterator();
        UUID oldestId = oldest.next();
        oldest.remove();
        insertionOrder.remove(oldestId);
    }

    // Entfernt die insgesamt aelteste Nachricht (globale Grenze). Ein leerer Raum wird ausgetragen.
    private void removeOldestOverall() {
        Iterator<Map.Entry<UUID, UUID>> oldest = insertionOrder.entrySet().iterator();
        Map.Entry<UUID, UUID> entry = oldest.next();
        UUID oldestId = entry.getKey();
        UUID roomId = entry.getValue();
        oldest.remove();
        LinkedHashMap<UUID, BufferedMessage> room = rooms.get(roomId);
        room.remove(oldestId);
        if (room.isEmpty()) {
            rooms.remove(roomId);
        }
    }
}
