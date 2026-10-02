package ch.m321.chatapp.chat.messaging;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ-Konfiguration des Chat-Service.
 *
 * chat-service veroeffentlicht direkt auf die Queue chat.persist, ohne
 * eigenen Exchange (siehe docs/spec-batch-writer.md, Abschnitt 3).
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

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
