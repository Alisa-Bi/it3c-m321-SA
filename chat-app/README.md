# Chat-App — Abgabe batch-writer

Zwischenstand für die Abgabe `batch-writer` (M321, IT3c). Umfang und
Abgrenzung: siehe `docs/spec-batch-writer.md`, Abschnitt 1. Architektur
insgesamt: `PLANUNG.md`.

## Struktur

```text
chat-app/
├── pom.xml                      Maven-Elternprojekt (PLANUNG.md 5)
├── docker/                      docker-compose.yml fuer diesen Abgabe-Stack
├── backend/
│   ├── chat-service/             Nimmt Nachrichten entgegen, validiert, publiziert
│   ├── batch-writer/              Einziger Datenbank-Schreiber
│   ├── api-gateway/, user-service/  nicht Teil dieser Abgabe, nicht Teil des Builds/Stacks
├── infrastructure/postgres/     Init-Skript fuer die Tabelle message
├── docs/
│   ├── spec-batch-writer.md      Spezifikation
│   └── plan-batch-writer.md      Umsetzungsplan, Schritt fuer Schritt
├── ARCHITECTURE.md               Bisherige Architekturentscheidungen (teils ueberholt)
└── .env.example                  POSTGRES_*/RABBITMQ_*-Variablen
```

## Starten

```bash
cp .env.example .env
cd docker
docker compose up -d --build
```

Kein Dienst veröffentlicht einen Port. `chat-service` ist unter
`POST http://chat-service:8082/messages` innerhalb des Netzwerks `chat-net`
erreichbar, z. B. über `docker compose exec`.

## Stand

| Bereich | Stand |
|---|---|
| Spezifikation (`docs/spec-batch-writer.md`) | fertig |
| Umsetzungsplan (`docs/plan-batch-writer.md`) | fertig |
| Eltern-POM (chat-service, batch-writer) | umgesetzt |
| chat-service: Vertrag, Endpunkt `/messages`, keine Auth/DB | umgesetzt |
| Tabelle `message` (Init-Skript) | umgesetzt |
| batch-writer: Grundgerüst | umgesetzt |
| batch-writer: RabbitMQ-Topologie (chat.persist, Retry-Queue, chat.dlq) | umgesetzt |
| batch-writer: Batch-Consumer (500/200ms) | umgesetzt |
| batch-writer: Bulk-Insert mit `ON CONFLICT DO NOTHING` | umgesetzt |
| batch-writer: Retry/DLQ (3 Versuche, 15s) | umgesetzt |
| docker-compose.yml, `.env.example` bereinigt | umgesetzt |
| Tests: Duplikat (S5), Datenbankausfall (S7) | umgesetzt |
| Vollständiger S1–S8-Durchlauf gegen echten Stack | **noch offen** |

## Kompilieren

Konnte in der Sandbox, in der dieses Repository entstand, nicht gegen
Maven Central geprüft werden (Netzwerk gesperrt). Bitte `mvn clean test`
im Repository-Root lokal ausführen und prüfen.
