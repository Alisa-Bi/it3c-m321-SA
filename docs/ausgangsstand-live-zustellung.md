# Ausgangsstand für die Live-Zustellung

Stand: Commit `c1490dc` (Spezifikation und Plan sind gepusht, noch kein Code für die Live-Zustellung).

Dieses Dokument beschreibt nur, was im Code und in der Konfiguration gelesen wurde. Es wurde nichts ausgeführt und kein Testergebnis erhoben. Es dient als Vergleichsbasis für die folgenden Schritte des Plans (`docs/plan-live-zustellung.md`).

## 1. Dienste im Compose-Stack

`docker-compose.yml` im Wurzelverzeichnis enthält vier Dienste: `postgres`, `rabbitmq`, `chat-service` und `batch-writer`. Alle hängen am Netz `chat-net`.

- Kein Dienst hat einen `ports:`-Eintrag. Auch die RabbitMQ-Management-Oberfläche ist nicht nach aussen veröffentlicht.
- Kein Dienst hat einen `container_name`. `chat-service` lässt sich daher mit `--scale` mehrfach starten.
- `chat-service` bekommt nur `RABBITMQ_DEFAULT_USER` und `RABBITMQ_DEFAULT_PASS` als Umgebungsvariablen. Er hat `depends_on: rabbitmq`, aber keine Datenbank-Variablen.
- `batch-writer` bekommt zusätzlich `POSTGRES_USER`, `POSTGRES_PASSWORD` und `POSTGRES_DB` und hängt von `postgres` und `rabbitmq` ab.
- `keycloak`, `api-gateway` und `user-service` sind nicht Teil des Stacks. Ihr Code liegt weiterhin im Repository.
- Die Projektregeln (`CLAUDE.md`) und `PLANUNG.md` liegen im Ordner `docs/`, nicht im Wurzelverzeichnis.

## 2. Der chat-service heute

Pfad: `backend/chat-service`. Port `8082` (in `application.yml`). Basis ist Spring Boot 3.3.5 über das Eltern-POM im Wurzelverzeichnis.

| Klasse | Aufgabe |
|---|---|
| `ChatServiceApplication` | Startklasse |
| `MessageController` | `POST /messages` nimmt eine `NewMessageRequest` entgegen und antwortet immer mit HTTP 202 |
| `MessageService` | prüft `roomId`, `senderId` und `content` (sonst HTTP 400 über `ResponseStatusException`), setzt `id` mit `UUID.randomUUID()` und `sentAt` mit `Instant.now()` und übergibt an den Producer |
| `MessageProducer` | sendet über `AmqpTemplate.convertAndSend` mit dem Routing-Key `chat.persist` (Standard-Exchange) |
| `RabbitMqConfig` | enthält die Konstante `PERSIST_QUEUE = "chat.persist"` und einen `Jackson2JsonMessageConverter` |
| `NewMessageRequest` | Record mit `id`, `roomId`, `senderId`, `senderName`, `content`, `sentAt` |

Der chat-service deklariert selbst keine Queue und keinen Exchange. `chat.persist` wird vom `batch-writer` angelegt.

Es gibt keine Behandlung für Fehler beim Veröffentlichen: Eine AMQP-Ausnahme wird nicht abgefangen. Es gibt keine Grössenbegrenzung für `content`. `sentAt` wird nicht auf Mikrosekunden gekürzt.

## 3. Konfiguration

`backend/chat-service/src/main/resources/application.yml`:

- RabbitMQ: Host `rabbitmq`, Port `5672`, Benutzer und Passwort aus `RABBITMQ_DEFAULT_USER` und `RABBITMQ_DEFAULT_PASS` (mit Standardwerten).
- Es sind weder ein RabbitMQ-Heartbeat noch Publisher Confirms gesetzt.
- Actuator gibt `health` und `info` frei.
- Es gibt keine Datenbank-Einstellungen.

## 4. Abhängigkeiten im `pom.xml` des chat-service

Vorhanden: `spring-boot-starter-web`, `spring-boot-starter-amqp`, `spring-boot-starter-actuator`, Lombok, `spring-boot-starter-test` und `spring-rabbit-test` (beide nur für Tests).

Nicht vorhanden: JDBC und PostgreSQL-Treiber, Testcontainers, Awaitility. Die Beschreibung im `pom.xml` sagt, der Dienst berühre keine Datenbank.

## 5. Tests im chat-service

- `MessageServiceTest` (Mockito): prüft, dass `id` und `sentAt` gesetzt werden.
- `MessageControllerTest` (`@WebMvcTest`): prüft, dass `POST /messages` mit HTTP 202 antwortet und den Service aufruft.

Es gibt keine Integrationstests mit echtem RabbitMQ im chat-service.

## 6. Datenbank

`infrastructure/postgres/init.sql` legt die Tabelle `message` an (`id` UUID als Primärschlüssel, `room_id` UUID, `sender_id` und `sender_name` VARCHAR(255), `content` TEXT, `sent_at` TIMESTAMPTZ) und den Index `idx_message_room_sent_at` auf `(room_id, sent_at DESC)`. Nur der `batch-writer` schreibt in diese Tabelle.

## 7. Docker-Image

`backend/chat-service/Dockerfile` baut mit Maven (`maven:3.9-eclipse-temurin-21`) und läuft auf `eclipse-temurin:21-jre-alpine`. Der Build-Kontext ist das Wurzelverzeichnis. `EXPOSE 8082` veröffentlicht keinen Port nach aussen.

## 8. Was für die Live-Zustellung noch fehlt

Gemessen an `docs/spec-live-zustellung.md`:

1. Veröffentlichen an den Exchange `chat.delivery` (zusätzlich zu `chat.persist`), Kürzung von `sentAt`, Grössenlimit mit HTTP 400, HTTP 503 bei AMQP-Fehlern.
2. Eine Queue pro Instanz am Exchange mit genau einem Consumer und der Verteilerklasse `LiveDispatcher`.
3. Der Endpunkt `GET /stream` mit `SseConnectionRegistry` und Heartbeats.
4. Verteilung nach Raum im SSE-Format.
5. Queue-Limit, Überlaufverhalten und Prefetch sowie der RabbitMQ-Heartbeat von 30 Sekunden.
6. Lesender Zugriff auf PostgreSQL ohne Startabhängigkeit.
7. Ringbuffer, Nachholen (`ReplayService`) und der Übergang vom Nachholen zur Live-Zustellung.
8. Testabhängigkeiten (Testcontainers, Awaitility) und alle neuen Tests.
9. Das Skript `scripts/pruefe-live-zustellung.ps1` für die Szenarien am laufenden Stack.
10. Neue Umgebungsvariablen in `docker-compose.yml` und `.env.example` (PostgreSQL-Zugang für den chat-service) sowie die `CHAT_*`-Werte in `application.yml`.