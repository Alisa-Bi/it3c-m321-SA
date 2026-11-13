-- Schema fuer batch-writer, nach docs/spec-batch-writer.md Abschnitt 4.1,
-- Spalten/Index exakt nach PLANUNG.md 3.7 (MESSAGE-Entitaet).
--
-- Entsteht hier und nur hier, beim ersten Start von PostgreSQL - weder
-- chat-service noch batch-writer verwalten das Schema zur Laufzeit
-- (batch-writer nutzt bewusst JdbcTemplate statt JPA, siehe PLANUNG.md 2.1).
-- Es gibt nur eine Datenbank (POSTGRES_DB), da ausser batch-writer kein
-- Dienst in diesem Abgabe-Stack die Datenbank beruehrt.

CREATE TABLE message (
    id          UUID PRIMARY KEY,
    room_id     UUID NOT NULL,
    sender_id   VARCHAR(255) NOT NULL,
    sender_name VARCHAR(255) NOT NULL,
    content     TEXT NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL
);

-- Einzige Abfrage im (hier nicht Teil der Abgabe befindlichen) Lesepfad:
-- "die letzten Nachrichten eines Raums" (PLANUNG.md 3.7).
CREATE INDEX idx_message_room_sent_at ON message (room_id, sent_at DESC);
