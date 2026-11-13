package ch.m321.chatapp.chat.messaging;

import ch.m321.chatapp.chat.delivery.LiveDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Konsumiert die eigene Zustell-Queue dieser Instanz und gibt jede Nachricht
 * an den LiveDispatcher weiter (siehe docs/spec-live-zustellung.md,
 * Abschnitte 3.2 und 4.2).
 *
 * Es gibt genau einen Consumer mit genau einem Thread. Dadurch werden
 * Nachrichten in der Reihenfolge verarbeitet, in der sie in der Queue lagen;
 * so bleibt die Reihenfolge je Absender erhalten.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DeliveryListener {

    private final LiveDispatcher liveDispatcher;

    // Die Queue wird ueber ihren Bean-Namen "deliveryQueue" gefunden
    // ("#{deliveryQueue.name}"), weil ihr Name bei jedem Start neu erzeugt wird.
    // Der Parameter ist die rohe Nachricht, damit der JSON-Text unveraendert
    // bleibt und nicht erst in ein Objekt und zurueck umgewandelt wird.
    @RabbitListener(queues = "#{deliveryQueue.name}", concurrency = "1")
    public void onMessage(Message message) {
        String messageJson = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            liveDispatcher.dispatch(messageJson);
        } catch (RuntimeException exception) {
            // Ein Fehler im Dispatcher darf keine erneute Zustellung ausloesen:
            // Eine Nachricht, die einmal fehlschlaegt, wuerde sonst endlos
            // wiederkehren und die Queue blockieren. Sie wird nur protokolliert.
            log.error("Dispatching a live message failed, message is dropped", exception);
        }
    }
}
