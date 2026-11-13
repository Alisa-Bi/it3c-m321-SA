package ch.m321.chatapp.chat.replay;

import ch.m321.chatapp.chat.delivery.BufferedMessage;
import ch.m321.chatapp.chat.delivery.RecentMessageBuffer;
import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.history.MessageHistoryRepository;
import ch.m321.chatapp.chat.history.MessagePosition;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bestimmt zu einer Last-Event-ID die verpassten Nachrichten eines Raums (docs/spec-live-zustellung.md,
 * Abschnitt 5). Diese Klasse liest nur und sendet nichts.
 *
 * Zwei Schritte, weil der Cursor geprueft sein muss, bevor HTTP 200 gesendet wird (Abschnitt 5.3):
 * resolveCursor sucht die Position der Last-Event-ID zuerst in der Datenbank, danach im Ringbuffer
 * des Raums. findMissed liefert danach die Nachrichten nach dieser Position aus Datenbank und
 * Ringbuffer, zusammengefuehrt, ohne Doppelte, sortiert nach (sentAt, id).
 *
 * Reihenfolge: sentAt aufsteigend, bei Gleichstand die id. Die ids werden wie in PostgreSQL
 * verglichen, also Byte fuer Byte ohne Vorzeichen. Der Vergleich von java.util.UUID waere falsch: Er
 * rechnet mit Vorzeichen und ordnet ids, die mit 8 bis f beginnen, vor die mit 0 bis 7. Dann stimmte
 * die Reihenfolge in Java nicht mit ORDER BY in der Datenbank ueberein.
 *
 * Das JSON einer Nachricht aus der Datenbank entsteht aus einem NewMessageRequest mit demselben
 * Jackson-Converter, der die Nachricht beim Veroeffentlichen erzeugt hat. Nachrichten aus dem
 * Ringbuffer behalten ihren urspruenglichen JSON-Text.
 *
 * Jeder Datenbankfehler wird zu einer ReplayUnavailableException (HTTP 503), auch wenn der Cursor
 * im Ringbuffer stuende: Der Ringbuffer allein ist keine vollstaendige Historie.
 */
@Service
public class ReplayService {

    // Reihenfolge der Nachrichten: sentAt, dann id ohne Vorzeichen.
    private static final Comparator<BufferedMessage> MESSAGE_ORDER =
            (first, second) -> compare(first.sentAt(), first.id(), second.sentAt(), second.id());

    private final MessageHistoryRepository repository;
    private final RecentMessageBuffer buffer;
    private final Jackson2JsonMessageConverter jsonConverter;

    // repository: lesender Zugriff auf die Datenbank. buffer: Ringbuffer dieser Instanz.
    // jsonConverter: derselbe Converter wie beim Veroeffentlichen.
    public ReplayService(MessageHistoryRepository repository, RecentMessageBuffer buffer,
                         Jackson2JsonMessageConverter jsonConverter) {
        this.repository = repository;
        this.buffer = buffer;
        this.jsonConverter = jsonConverter;
    }

    // Sucht die Position der Last-Event-ID. Datenbank zuerst: Gehoert die Nachricht dort zu einem
    // anderen Raum, ist das ein Fehler (400). Sonst Ringbuffer des Raums. Gefunden wird nirgends: 409.
    // Datenbank nicht erreichbar: 503.
    public MessagePosition resolveCursor(UUID roomId, UUID lastEventId) {
        Optional<MessagePosition> stored;
        try {
            stored = repository.findPosition(lastEventId);
        } catch (DataAccessException exception) {
            throw new ReplayUnavailableException(exception);
        }
        if (stored.isPresent()) {
            if (!stored.get().roomId().equals(roomId)) {
                throw new InvalidCursorException();
            }
            return stored.get();
        }

        Optional<BufferedMessage> buffered = buffer.find(roomId, lastEventId);
        if (buffered.isPresent()) {
            return new MessagePosition(lastEventId, roomId, buffered.get().sentAt());
        }
        throw new CursorNotFoundException();
    }

    // Liefert alle Nachrichten des Raums nach der Position, ohne Doppelte, nach (sentAt, id) sortiert.
    // Der Schnappschuss des Ringbuffers entsteht zuerst, danach kommt die Datenbankabfrage.
    public List<BufferedMessage> findMissed(UUID roomId, MessagePosition cursor) {
        List<BufferedMessage> snapshot = buffer.snapshot(roomId);

        List<NewMessageRequest> stored;
        try {
            stored = repository.findAfter(roomId, cursor);
        } catch (DataAccessException exception) {
            throw new ReplayUnavailableException(exception);
        }

        // Nach id zusammenfuehren: Steht eine Nachricht in beiden Quellen, bleibt der urspruengliche
        // JSON-Text aus dem Ringbuffer.
        LinkedHashMap<UUID, BufferedMessage> merged = new LinkedHashMap<>();
        for (NewMessageRequest row : stored) {
            merged.put(row.id(), new BufferedMessage(row.id(), row.roomId(), row.sentAt(), toJson(row)));
        }
        for (BufferedMessage message : snapshot) {
            if (compare(message.sentAt(), message.id(), cursor.sentAt(), cursor.id()) > 0) {
                merged.put(message.id(), message);
            }
        }

        List<BufferedMessage> result = new ArrayList<>(merged.values());
        result.sort(MESSAGE_ORDER);
        return result;
    }

    // Beide Schritte zusammen: zu roomId und Last-Event-ID die verpassten Nachrichten.
    public List<BufferedMessage> missedMessages(UUID roomId, UUID lastEventId) {
        MessagePosition cursor = resolveCursor(roomId, lastEventId);
        return findMissed(roomId, cursor);
    }

    // Erzeugt den JSON-Text einer Datenbankzeile mit dem Converter des Veroeffentlichens.
    private String toJson(NewMessageRequest row) {
        Message message = jsonConverter.toMessage(row, new MessageProperties());
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    // Vergleicht zwei Positionen: zuerst sentAt, bei Gleichstand die ids als 128-Bit-Zahl ohne Vorzeichen.
    private static int compare(Instant firstTime, UUID firstId, Instant secondTime, UUID secondId) {
        int byTime = firstTime.compareTo(secondTime);
        if (byTime != 0) {
            return byTime;
        }
        int byHighBits = Long.compareUnsigned(firstId.getMostSignificantBits(), secondId.getMostSignificantBits());
        if (byHighBits != 0) {
            return byHighBits;
        }
        return Long.compareUnsigned(firstId.getLeastSignificantBits(), secondId.getLeastSignificantBits());
    }
}
