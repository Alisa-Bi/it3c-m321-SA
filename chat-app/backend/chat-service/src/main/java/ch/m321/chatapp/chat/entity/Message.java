package ch.m321.chatapp.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine einzelne Chatnachricht innerhalb eines Chatraums.
 *
 * Der Index auf (roomId, createdAt) beschleunigt den bestehenden Lesepfad
 * (MessageRepository.findByRoomIdOrderByCreatedAtAsc). Der UNIQUE-Constraint
 * auf messageId ist der Idempotenz-Schluessel fuer den spaeteren
 * batch-writer (siehe docs/spec-batch-writer.md, Abschnitt 4 und 5): zwei
 * Zeilen mit derselben messageId sind auf Datenbankebene ausgeschlossen,
 * nicht nur per Anwendungslogik geprueft.
 */
@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_room_created_at", columnList = "roomId, createdAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Fachlicher Idempotenz-Schluessel, von chat-service beim Publizieren
    // erzeugt (siehe NewMessageRequest). Nicht zu verwechseln mit "id" oben,
    // das ist der rein technische Primaerschluessel dieser Tabelle.
    @Column(nullable = false, unique = true)
    private UUID messageId;

    private UUID roomId;

    private UUID senderId;

    @Column(columnDefinition = "TEXT")
    private String content;

    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    private MessageStatus status;
}
