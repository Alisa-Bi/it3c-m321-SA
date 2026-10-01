package ch.m321.chatapp.chat.mapper;

import ch.m321.chatapp.chat.dto.MessageDto;
import ch.m321.chatapp.chat.entity.Message;
import org.springframework.stereotype.Component;

/**
 * Wandelt zwischen Message-Entitaet und MessageDto um.
 */
@Component
public class MessageMapper {

    public MessageDto toDto(Message message) {
        return new MessageDto(
                message.getId(),
                message.getRoomId(),
                message.getSenderId(),
                message.getContent(),
                message.getCreatedAt(),
                message.getStatus());
    }
}
