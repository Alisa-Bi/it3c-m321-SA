package ch.m321.chatapp.batchwriter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Liest rohe Nachrichten aus chat.persist, ermittelt die bisherige Anzahl
 * Zustellversuche aus dem von RabbitMQ selbst mitgefuehrten x-death-Header
 * und reicht gueltige Nachrichten an den BatchBuffer weiter.
 *
 * Verarbeitet die rohe AMQP-Message statt eines automatisch konvertierten
 * Objekts, weil Channel und deliveryTag fuer das spaetere manuelle
 * Ack/Nack gebraucht werden (manuelles Ack ist in application.yml
 * konfiguriert, siehe docs/spec-batch-writer.md, Abschnitt 5.1).
 */
@Component
@Slf4j
public class MessageConsumer {

    private final BatchBuffer batchBuffer;
    private final AmqpTemplate amqpTemplate;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public MessageConsumer(BatchBuffer batchBuffer, AmqpTemplate amqpTemplate, ObjectMapper objectMapper,
                            @Value("${batch-writer.max-attempts}") int maxAttempts) {
        this.batchBuffer = batchBuffer;
        this.amqpTemplate = amqpTemplate;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
    }

    @RabbitListener(queues = RabbitMqConfig.PERSIST_QUEUE, containerFactory = RabbitMqConfig.RAW_LISTENER_FACTORY)
    public void onMessage(Message rawMessage, Channel channel) throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
        int attempt = readAttemptCount(rawMessage.getMessageProperties()) + 1;

        if (attempt > maxAttempts) {
            sendRawToDeadLetterQueue(rawMessage, channel, deliveryTag);
            return;
        }

        try {
            IncomingMessage incomingMessage = objectMapper.readValue(rawMessage.getBody(), IncomingMessage.class);
            batchBuffer.add(new PendingMessage(incomingMessage, channel, deliveryTag, attempt));
        } catch (Exception parseFailed) {
            log.warn("Nachricht konnte nicht gelesen werden (Versuch {}/{}): {}", attempt, maxAttempts, parseFailed.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }

    // RabbitMQ haengt bei jedem Dead-Letter-Durchlauf einen Eintrag an den
    // x-death-Header an. Der "count" des ersten Eintrags ist die Anzahl
    // bisheriger fehlgeschlagener Versuche fuer chat.persist.
    @SuppressWarnings("unchecked")
    private int readAttemptCount(MessageProperties properties) {
        Object xDeath = properties.getHeaders().get("x-death");
        if (!(xDeath instanceof List<?> deaths) || deaths.isEmpty()) {
            return 0;
        }
        Map<String, Object> firstEntry = (Map<String, Object>) deaths.get(0);
        Object count = firstEntry.get("count");
        if (count instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    // Sicherheitsnetz: eine Nachricht, die die maximale Anzahl Versuche
    // bereits ueberschritten hat (z. B. weil sie wiederholt nicht einmal
    // gelesen werden konnte), geht unverarbeitet direkt nach chat.dlq,
    // statt die Queue dauerhaft zu blockieren.
    private void sendRawToDeadLetterQueue(Message rawMessage, Channel channel, long deliveryTag) throws IOException {
        log.error("Nachricht hat die maximale Anzahl Versuche ueberschritten, geht nach chat.dlq");
        amqpTemplate.send(RabbitMqConfig.DLQ, rawMessage);
        channel.basicAck(deliveryTag, false);
    }
}
