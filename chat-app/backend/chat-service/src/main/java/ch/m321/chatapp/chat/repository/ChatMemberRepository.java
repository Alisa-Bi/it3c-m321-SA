package ch.m321.chatapp.chat.repository;

import ch.m321.chatapp.chat.entity.ChatMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Datenzugriff fuer die Mitgliedschaft in Chatraeumen.
 */
public interface ChatMemberRepository extends JpaRepository<ChatMember, UUID> {

    List<ChatMember> findByRoomId(UUID roomId);
}
