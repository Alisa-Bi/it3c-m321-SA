package ch.m321.chatapp.chat.messaging;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ-Konfiguration des Chat-Service.
 *
 * chat-service veroeffentlicht jede Nachricht auf zwei Wegen: direkt auf die
 * Queue chat.persist (ohne eigenen Exchange, siehe docs/spec-batch-writer.md,
 * Abschnitt 3) und zusaetzlich an den Fanout-Exchange chat.delivery fuer die
 * Live-Zustellung (siehe docs/spec-live-zustellung.md, Abschnitt 3).
 *
 * Kein eigener RabbitTemplate-Bean mehr: Spring Boot legt beim Vorhandensein
 * von spring-boot-starter-amqp automatisch einen RabbitTemplate an und
 * zieht sich dafuer jeden vorhandenen MessageConverter-Bean selbst -
 * ein eigener, gleichnamiger Bean fuehrte zu einer Namenskollision, da
 * Spring Boots @ConditionalOnMissingBean(RabbitTemplate.class) gegen den
 * konkreten Typ prueft, nicht gegen das hier verwendete AmqpTemplate-Interface.
 */
@Configuration
public class RabbitMqConfig {

    public static final String PERSIST_QUEUE = "chat.persist";
    public static final String DELIVERY_EXCHANGE = "chat.delivery";

    // Legt den Fanout-Exchange fuer die Live-Zustellung an. Jede Instanz
    // deklariert ihn beim Start; RabbitMQ ignoriert die Deklaration, wenn er
    // schon mit denselben Eigenschaften existiert. Dauerhaft, damit er einen
    // Neustart von RabbitMQ ueberlebt, und nicht auto-delete, damit er nicht
    // verschwindet, wenn gerade keine Queue daran haengt.
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(DELIVERY_EXCHANGE, true, false);
    }

    // Legt die eigene Zustell-Queue dieser Instanz an. Eine AnonymousQueue hat
    // einen eindeutigen, automatisch erzeugten Namen und ist exklusiv (gehoert
    // zur Verbindung und verschwindet mit ihr), auto-delete (verschwindet, wenn
    // der letzte Consumer weg ist) und nicht dauerhaft. So hat jede Instanz
    // ihre eigene Kopie jeder Nachricht und nach dem Beenden bleibt nichts
    // liegen (siehe Spezifikation, Abschnitt 3.2).
    @Bean
    public Queue deliveryQueue() {
        return new AnonymousQueue();
    }

    // Verbindet die Zustell-Queue mit dem Fanout-Exchange. Ein Fanout braucht
    // keinen Routing-Key.
    @Bean
    public Binding deliveryBinding(Queue deliveryQueue, FanoutExchange deliveryExchange) {
        return BindingBuilder.bind(deliveryQueue).to(deliveryExchange);
    }

    // Wandelt Nachrichten in JSON um. Beide Wege (chat.persist und
    // chat.delivery) verwenden diesen Converter und senden dadurch denselben
    // JSON-Text.
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
