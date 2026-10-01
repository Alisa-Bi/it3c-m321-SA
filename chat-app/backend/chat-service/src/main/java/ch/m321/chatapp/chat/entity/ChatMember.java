package ch.m321.chatapp.chat.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Verknuepft einen Benutzer mit einem Chatraum.
 * Hinweis: Die Planung sieht roomId/userId als zusammengesetzten Schluessel
 * vor. Fuer die Einfachheit verwenden wir hier einen zusaetzlichen
 * technischen Id-Wert statt eines zusammengesetzten Schluessels
 * (@EmbeddedId) - das spart Boilerplate und ist fuer Einsteiger leichter
 * nachvollziehbar (siehe ARCHITECTURE.md, Punkt 7).
 */
@Entity
@Table(name = "chat_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID roomId;

    private UUID userId;

    private Instant joinedAt;
}
