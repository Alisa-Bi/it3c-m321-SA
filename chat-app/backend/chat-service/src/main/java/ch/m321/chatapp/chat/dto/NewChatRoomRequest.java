package ch.m321.chatapp.chat.dto;

import ch.m321.chatapp.chat.entity.ChatRoomType;

/**
 * Was ein Client schickt, um einen neuen Chatraum anzulegen.
 */
public record NewChatRoomRequest(String name, ChatRoomType type) {
}
