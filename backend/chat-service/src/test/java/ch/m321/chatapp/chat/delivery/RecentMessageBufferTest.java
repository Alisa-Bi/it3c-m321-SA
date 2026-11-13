package ch.m321.chatapp.chat.delivery;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft den Ringbuffer ohne Spring: Grenze pro Raum, globale Grenze, Suche nach Raum,
 * doppelte ids, Schnappschuss als Kopie und parallele Zugriffe.
 */
class RecentMessageBufferTest {

    // Erzeugt eine Nachricht mit der Nummer im Inhalt und aufsteigendem Zeitstempel.
    private BufferedMessage message(UUID roomId, int number) {
        return new BufferedMessage(UUID.randomUUID(), roomId, Instant.ofEpochSecond(1_000_000 + number), "{\"n\":" + number + "}");
    }

    // Liest die Nummern aus den JSON-Texten eines Schnappschusses, in dessen Reihenfolge.
    private List<Integer> numbers(List<BufferedMessage> messages) {
        List<Integer> result = new ArrayList<>();
        for (BufferedMessage message : messages) {
            String json = message.json();
            result.add(Integer.parseInt(json.substring(5, json.length() - 1)));
        }
        return result;
    }

    // Bei N+10 Nachrichten bleiben genau die letzten N, in der Reihenfolge des Eintrags.
    @Test
    void perRoomLimit_keepsExactlyTheLastN() {
        RecentMessageBuffer buffer = new RecentMessageBuffer(5, 100);
        UUID room = UUID.randomUUID();

        for (int i = 0; i < 15; i++) {
            buffer.add(message(room, i));
        }

        assertThat(numbers(buffer.snapshot(room))).containsExactly(10, 11, 12, 13, 14);
        assertThat(buffer.roomSize(room)).isEqualTo(5);
        assertThat(buffer.totalSize()).isEqualTo(5);
    }

    // Die globale Grenze entfernt die insgesamt aelteste Nachricht, auch aus einem anderen Raum.
    @Test
    void totalLimit_removesGloballyOldest() {
        RecentMessageBuffer buffer = new RecentMessageBuffer(5, 8);
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            buffer.add(message(roomA, i));
        }
        for (int i = 10; i < 15; i++) {
            buffer.add(message(roomB, i));
        }

        // 10 Nachrichten, Grenze 8: die zwei aeltesten (beide aus Raum A) fallen heraus.
        assertThat(buffer.totalSize()).isEqualTo(8);
        assertThat(numbers(buffer.snapshot(roomA))).containsExactly(2, 3, 4);
        assertThat(numbers(buffer.snapshot(roomB))).containsExactly(10, 11, 12, 13, 14);
    }

    // Die Grenze pro Raum gilt unabhaengig von der globalen Grenze.
    @Test
    void perRoomLimit_holdsEvenWhenTotalLimitIsNotReached() {
        RecentMessageBuffer buffer = new RecentMessageBuffer(3, 1000);
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            buffer.add(message(roomA, i));
        }
        buffer.add(message(roomB, 100));

        assertThat(buffer.roomSize(roomA)).isEqualTo(3);
        assertThat(buffer.roomSize(roomB)).isEqualTo(1);
        assertThat(buffer.totalSize()).isEqualTo(4);
    }

    // Ein Raum ohne Nachrichten wird ausgetragen, damit er keinen Speicher belegt.
    @Test
    void roomWithoutMessages_isRemoved() {
        RecentMessageBuffer buffer = new RecentMessageBuffer(5, 2);
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();

        buffer.add(message(roomA, 1));
        buffer.add(message(roomB, 2));
        buffer.add(message(roomB, 3));

        assertThat(buffer.roomCount()).isEqualTo(1);
        assertThat(buffer.snapshot(roomA)).isEmpty();
        assertThat(buffer.roomSize(roomA)).isZero();
    }

    // find sucht nur im angegebenen Raum: Eine Nachricht eines anderen Raums wird nicht gefunden.
    @Test
    void find_looksOnlyInTheGivenRoom() {
        RecentMessageBuffer buffer = new RecentMessageBuffer(5, 100);
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        BufferedMessage inA = message(roomA, 1);
        buffer.add(inA);

        assertThat(buffer.find(roomA, inA.id())).contains(inA);
        assertThat(buffer.find(roomB, inA.id())).isEmpty();
        assertThat(buffer.find(roomA, UUID.randomUUID())).isEmpty();
    }

    // Eine Nachricht mit bereits bekannter id wird nicht doppelt eingetragen.
    @Test
    void duplicateId_isIgnored() {
        RecentMessageBuffer buffer = new RecentMessageBuffer(5, 100);
        UUID room = UUID.randomUUID();
        BufferedMessage first = message(room, 1);

        buffer.add(first);
        buffer.add(first);

        assertThat(buffer.snapshot(room)).hasSize(1);
        assertThat(buffer.totalSize()).isEqualTo(1);
    }

    // Der Schnappschuss ist eine Kopie: Spaetere Eintraege veraendern ihn nicht.
    @Test
    void snapshot_isACopy() {
        RecentMessageBuffer buffer = new RecentMessageBuffer(5, 100);
        UUID room = UUID.randomUUID();
        buffer.add(message(room, 1));

        List<BufferedMessage> snapshot = buffer.snapshot(room);
        buffer.add(message(room, 2));

        assertThat(snapshot).hasSize(1);
        assertThat(buffer.snapshot(room)).hasSize(2);
    }

    // Grenzen unter 1 sind ein Konfigurationsfehler.
    @Test
    void limitsBelowOne_areRejected() {
        assertThatThrownBy(() -> new RecentMessageBuffer(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecentMessageBuffer(10, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // Mehrere Threads schreiben und lesen gleichzeitig: keine Ausnahmen, die Grenzen bleiben eingehalten.
    @Test
    void parallelAccess_causesNoErrorsAndKeepsLimits() throws Exception {
        int perRoom = 50;
        int total = 120;
        RecentMessageBuffer buffer = new RecentMessageBuffer(perRoom, total);
        List<UUID> rooms = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rooms.add(UUID.randomUUID());
        }

        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> tasks = new ArrayList<>();
        for (int writer = 0; writer < 4; writer++) {
            UUID room = rooms.get(writer);
            tasks.add(executor.submit(() -> {
                for (int i = 0; i < 2000; i++) {
                    buffer.add(message(room, i));
                }
            }));
        }
        for (int reader = 0; reader < 4; reader++) {
            UUID room = rooms.get(reader);
            tasks.add(executor.submit(() -> {
                for (int i = 0; i < 2000; i++) {
                    List<BufferedMessage> snapshot = buffer.snapshot(room);
                    assertThat(snapshot.size()).isLessThanOrEqualTo(perRoom);
                    buffer.find(room, UUID.randomUUID());
                }
            }));
        }
        for (Future<?> task : tasks) {
            task.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(buffer.totalSize()).isLessThanOrEqualTo(total);
        for (UUID room : rooms) {
            assertThat(buffer.roomSize(room)).isLessThanOrEqualTo(perRoom);
        }
    }
}
