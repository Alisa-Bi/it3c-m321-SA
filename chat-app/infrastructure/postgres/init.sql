-- Legt fuer jeden Service eine eigene Datenbank an.
-- So bleibt die Datenhoheit pro Microservice erhalten, auch wenn (vorerst)
-- nur eine gemeinsame Postgres-Instanz betrieben wird (siehe ARCHITECTURE.md, Punkt 2).

CREATE DATABASE user_db;
CREATE DATABASE chat_db;
