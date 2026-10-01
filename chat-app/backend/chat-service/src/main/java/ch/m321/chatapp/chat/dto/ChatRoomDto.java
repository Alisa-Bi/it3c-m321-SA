package ch.m321.chatapp.chat.dto;

import ch.m321.chatapp.chat.entity.ChatRoomType;

import java.time.Instant;
import java.util.UUID;

/**
 * Oeffentliche Sicht auf einen Chatraum.
 */
public record ChatRoomDto(UUID id, String name, ChatRoomType type, Instant createdAt) {
}
