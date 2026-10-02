package ch.m321.chatapp.batchwriter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ-Topologie des batch-writer (siehe docs/spec-batch-writer.md,
 * Abschnitt 3).
 *
 * chat.persist ist die Arbeits-Queue. Lehnt der Consumer eine Nachricht ab
 * (basicNack ohne Requeue), leitet ihr x-dead-letter-exchange-Argument sie
 * automatisch an die Retry-Queue weiter. Die Retry-Queue haelt sie dort
 * fuer 15 Sekunden (x-message-ttl) und gibt sie danach automatisch an
 * chat.persist zurueck - RabbitMQ selbst erledigt die Verzoegerung, kein
 * aktives Warten im Anwendungscode. Nach insgesamt 3 Versuchen entscheidet
 * RetryHandler/MessageConsumer, die Nachricht stattdessen explizit nach
 * chat.dlq zu schicken.
 */
@Configuration
public class RabbitMqConfig {

    public static final String PERSIST_QUEUE = "chat.persist";
    public static final String RETRY_EXCHANGE = "chat.retry-exchange";
    public static final String RETRY_QUEUE = "chat.retry-queue";
    public static final String DLQ = "chat.dlq";
    public static final String RAW_LISTENER_FACTORY = "rawMessageListenerContainerFactory";

    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(PERSIST_QUEUE)
                .withArgument("x-dead-letter-exchange", RETRY_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", PERSIST_QUEUE)
                .build();
    }

    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(RETRY_EXCHANGE);
    }

    @Bean
    public Queue retryQueue(@Value("${batch-writer.retry-delay-ms}") int retryDelayMs) {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-message-ttl", retryDelayMs)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", PERSIST_QUEUE)
                .build();
    }

    @Bean
    public Binding retryBinding(Queue retryQueue, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue).to(retryExchange).with(PERSIST_QUEUE);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    // Wird vom RabbitTemplate zum SENDEN benutzt (z. B. RetryHandler, der
    // nach chat.dlq veroeffentlicht) - dafuer brauchen wir echtes
    // JSON-Marshalling. Fuer den EMPFANG in chat.persist wird bewusst NICHT
    // dieser Converter verwendet (siehe rawMessageListenerContainerFactory
    // unten), weil er beim Lesen den __TypeId__-Header auswerten wuerde -
    // und der zeigt auf eine Klasse aus chat-service, die es in batch-writer
    // nie geben wird (bewusst getrennte DTOs pro Service).
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // Eigene Listener-Factory nur fuer den Empfang aus chat.persist, mit
    // SimpleMessageConverter statt Jackson: SimpleMessageConverter liest
    // ausschliesslich die Rohbytes, ohne jemals eine Java-Klasse anhand
    // irgendeines Headers aufzuloesen. MessageConsumer parst das JSON
    // danach selbst (siehe dort) - die Nachricht muss also gar nicht
    // automatisch konvertiert werden.
    @Bean(RAW_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory rawMessageListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${batch-writer.batch-size}") int prefetchCount) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(prefetchCount);
        return factory;
    }

    // batch-writer hat keinen Web-Starter, der ueblicherweise einen
    // ObjectMapper mitbringt. MessageConsumer braucht ihn zum manuellen
    // Parsen der Rohdaten (siehe dort) - hier explizit bereitgestellt,
    // damit die Abhaengigkeit nicht unausgesprochen bleibt.
    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }
}
