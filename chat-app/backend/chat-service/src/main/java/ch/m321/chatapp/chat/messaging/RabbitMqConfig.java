package ch.m321.chatapp.chat.messaging;

import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ-Konfiguration des Chat-Service.
 *
 * Definiert Exchange, Queue und Binding fuer den Nachrichtentransport sowie
 * die JSON-Serialisierung. Transportiert wird ein NewMessageRequest (siehe
 * MessageProducer/MessageConsumer) - Producer und Consumer laufen im
 * gleichen Service, daher ist keine gemeinsame "Events"-Bibliothek noetig.
 * Bewusst hier im Code definiert statt in einer separaten
 * RabbitMQ-Definitionsdatei, naeher am Code, der die Werte tatsaechlich
 * benutzt.
 */
@Configuration
public class RabbitMqConfig {

    public static final String CHAT_EXCHANGE = "chat.exchange";
    public static final String MESSAGE_QUEUE = "chat.message.queue";
    public static final String MESSAGE_ROUTING_KEY = "chat.message";

    @Bean
    public TopicExchange chatExchange() {
        return new TopicExchange(CHAT_EXCHANGE);
    }

    @Bean
    public Queue messageQueue() {
        return new Queue(MESSAGE_QUEUE, true);
    }

    @Bean
    public Binding messageBinding(Queue messageQueue, TopicExchange chatExchange) {
        return BindingBuilder.bind(messageQueue).to(chatExchange).with(MESSAGE_ROUTING_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public AmqpTemplate chatRabbitTemplate(ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
