package ch.m321.chatapp.chat.dto;

import ch.m321.chatapp.chat.entity.MessageStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Oeffentliche Sicht auf eine Chatnachricht.
 * Wird sowohl ueber REST als auch ueber RabbitMQ als JSON verschickt.
 */
public record MessageDto(UUID id, UUID roomId, UUID senderId, String content, Instant createdAt, MessageStatus status) {
}
