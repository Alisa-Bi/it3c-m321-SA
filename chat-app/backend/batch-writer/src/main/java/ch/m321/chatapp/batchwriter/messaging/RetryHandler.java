package ch.m321.chatapp.batchwriter.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entscheidet nach einem fehlgeschlagenen Batch, ob eine Nachricht erneut
 * versucht oder nach chat.dlq verschoben wird (siehe
 * docs/spec-batch-writer.md, Abschnitt 3 und 5.3/5.4 - bis zu 3 Versuche
 * mit 15 Sekunden Abstand ueber die Retry-Queue, danach chat.dlq).
 */
@Component
@Slf4j
public class RetryHandler {

    private final AmqpTemplate amqpTemplate;
    private final int maxAttempts;

    public RetryHandler(AmqpTemplate amqpTemplate, @Value("${batch-writer.max-attempts}") int maxAttempts) {
        this.amqpTemplate = amqpTemplate;
        this.maxAttempts = maxAttempts;
    }

    public void handleFailedBatch(List<PendingMessage> batch) {
        for (PendingMessage pendingMessage : batch) {
            handleFailedMessage(pendingMessage);
        }
    }

    private void handleFailedMessage(PendingMessage pendingMessage) {
        if (pendingMessage.attempt() >= maxAttempts) {
            sendToDeadLetterQueue(pendingMessage);
        } else {
            rejectForRetry(pendingMessage);
        }
    }

    // Versuche < 3: Nachricht ohne Requeue ablehnen - chat.persist leitet
    // sie ueber ihr Dead-Letter-Argument in die Retry-Queue, die sie nach
    // 15 Sekunden automatisch zurueckgibt (siehe RabbitMqConfig).
    private void rejectForRetry(PendingMessage pendingMessage) {
        try {
            pendingMessage.channel().basicNack(pendingMessage.deliveryTag(), false, false);
        } catch (Exception rejectFailed) {
            log.error("Reject fuer Nachricht {} fehlgeschlagen: {}", pendingMessage.message().id(), rejectFailed.getMessage());
        }
    }

    // Versuch 3 ist ebenfalls fehlgeschlagen: direkt nach chat.dlq
    // veroeffentlichen und die Originalnachricht danach bestaetigen, damit
    // sie nicht noch einmal ueber die Retry-Queue zurueckkommt.
    private void sendToDeadLetterQueue(PendingMessage pendingMessage) {
        log.error("Nachricht {} nach {} Versuchen nicht verarbeitbar, geht nach chat.dlq",
                pendingMessage.message().id(), maxAttempts);
        amqpTemplate.convertAndSend(RabbitMqConfig.DLQ, pendingMessage.message());
        try {
            pendingMessage.channel().basicAck(pendingMessage.deliveryTag(), false);
        } catch (Exception ackFailed) {
            log.error("Ack nach DLQ-Versand fuer Nachricht {} fehlgeschlagen: {}", pendingMessage.message().id(), ackFailed.getMessage());
        }
    }
}
