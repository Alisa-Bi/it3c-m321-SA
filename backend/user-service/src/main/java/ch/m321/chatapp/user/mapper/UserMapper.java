package ch.m321.chatapp.user.mapper;

import ch.m321.chatapp.user.dto.UserDto;
import ch.m321.chatapp.user.entity.User;
import org.springframework.stereotype.Component;

/**
 * Wandelt zwischen der internen User-Entitaet und dem oeffentlichen UserDto
 * um. Bewusst eine eigene Klasse statt eines Interfaces, da es nur eine
 * Umsetzung gibt (siehe CLAUDE.md: keine Vorrats-Abstraktionen).
 */
@Component
public class UserMapper {

    public UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getUsername(), user.getDisplayName());
    }
}
