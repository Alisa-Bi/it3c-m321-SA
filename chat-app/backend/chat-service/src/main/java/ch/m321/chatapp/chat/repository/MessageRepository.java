package ch.m321.chatapp.chat.repository;

import ch.m321.chatapp.chat.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Datenzugriff fuer Chatnachrichten.
 */
public interface MessageRepository extends JpaRepository<Message, UUID> {

    // Liefert die Nachrichtenhistorie eines Chatraums, sortiert nach Erstellungszeit.
    List<Message> findByRoomIdOrderByCreatedAtAsc(UUID roomId);
}
