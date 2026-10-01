package ch.m321.chatapp.chat.mapper;

import ch.m321.chatapp.chat.dto.ChatRoomDto;
import ch.m321.chatapp.chat.entity.ChatRoom;
import org.springframework.stereotype.Component;

/**
 * Wandelt zwischen ChatRoom-Entitaet und ChatRoomDto um.
 */
@Component
public class ChatRoomMapper {

    public ChatRoomDto toDto(ChatRoom chatRoom) {
        return new ChatRoomDto(chatRoom.getId(), chatRoom.getName(), chatRoom.getType(), chatRoom.getCreatedAt());
    }
}
