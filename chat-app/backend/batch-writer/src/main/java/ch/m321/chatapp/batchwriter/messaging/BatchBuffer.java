package ch.m321.chatapp.batchwriter.messaging;

import ch.m321.chatapp.batchwriter.persistence.MessageWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Sammelt Nachrichten, bis die konfigurierte Batch-Groesse erreicht ist
 * oder die maximale Wartezeit abgelaufen ist (siehe
 * docs/spec-batch-writer.md, Abschnitt 5.1 - Werte aus PLANUNG.md 3.6/4.1:
 * 500 Nachrichten oder 200 ms, je nachdem was zuerst eintritt).
 */
@Component
@Slf4j
public class BatchBuffer {

    private final List<PendingMessage> buffer = new ArrayList<>();
    private final Object lock = new Object();

    private final MessageWriter messageWriter;
    private final RetryHandler retryHandler;
    private final int batchSize;

    public BatchBuffer(MessageWriter messageWriter, RetryHandler retryHandler,
                        @Value("${batch-writer.batch-size}") int batchSize) {
        this.messageWriter = messageWriter;
        this.retryHandler = retryHandler;
        this.batchSize = batchSize;
    }

    // Wird vom Consumer fuer jede eingehende Nachricht aufgerufen. Fuellt
    // den Puffer und loest sofort einen Flush aus, sobald die Batch-Groesse
    // erreicht ist - ohne auf den naechsten Scheduler-Tick zu warten.
    public void add(PendingMessage pendingMessage) {
        List<PendingMessage> batchToFlush = null;
        synchronized (lock) {
            buffer.add(pendingMessage);
            if (buffer.size() >= batchSize) {
                batchToFlush = new ArrayList<>(buffer);
                buffer.clear();
            }
        }
        if (batchToFlush != null) {
            flush(batchToFlush);
        }
    }

    // Wird alle 200 ms vom Scheduler aufgerufen, damit auch bei geringer
    // Last gepufferte Nachrichten nicht unbegrenzt liegen bleiben (wichtig
    // fuer S7: nur 300 Nachrichten, nie 500 am Stueck).
    @Scheduled(fixedDelayString = "${batch-writer.max-wait-ms}")
    public void flushOnTimeout() {
        List<PendingMessage> batchToFlush = null;
        synchronized (lock) {
            if (!buffer.isEmpty()) {
                batchToFlush = new ArrayList<>(buffer);
                buffer.clear();
            }
        }
        if (batchToFlush != null) {
            flush(batchToFlush);
        }
    }

    // Schreibt den Stapel in einer Transaktion und bestaetigt ihn danach
    // gemeinsam. Schlaegt das Schreiben fehl, uebernimmt der RetryHandler.
    private void flush(List<PendingMessage> batch) {
        try {
            messageWriter.writeBatch(batch);
            acknowledge(batch);
        } catch (Exception writeFailed) {
            log.warn("Batch von {} Nachrichten konnte nicht gespeichert werden: {}", batch.size(), writeFailed.getMessage());
            retryHandler.handleFailedBatch(batch);
        }
    }

    private void acknowledge(List<PendingMessage> batch) {
        for (PendingMessage pendingMessage : batch) {
            try {
                pendingMessage.channel().basicAck(pendingMessage.deliveryTag(), false);
            } catch (Exception ackFailed) {
                log.error("Ack fuer Nachricht {} fehlgeschlagen: {}", pendingMessage.message().id(), ackFailed.getMessage());
            }
        }
    }
}
