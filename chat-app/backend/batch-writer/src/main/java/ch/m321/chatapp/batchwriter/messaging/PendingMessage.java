package ch.m321.chatapp.batchwriter.messaging;

import com.rabbitmq.client.Channel;

/**
 * Eine aus RabbitMQ gelesene, noch nicht bestaetigte Nachricht samt allem,
 * was fuer das spaetere Ack/Nack gebraucht wird. attempt ist die laufende
 * Versuchsnummer (1 beim ersten Versuch), ermittelt aus dem
 * x-death-Header (siehe MessageConsumer).
 */
public record PendingMessage(IncomingMessage message, Channel channel, long deliveryTag, int attempt) {
}
