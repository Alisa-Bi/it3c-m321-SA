package ch.m321.chatapp.chat.repository;

import ch.m321.chatapp.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Datenzugriff fuer Chatraeume. Spring Data JPA erzeugt die Implementierung automatisch.
 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, UUID> {
}
