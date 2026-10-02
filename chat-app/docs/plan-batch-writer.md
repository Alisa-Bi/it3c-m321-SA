# Umsetzungsplan: batch-writer

Grundlage: `docs/spec-batch-writer.md` (Fassung nach PLANUNG.md-Korrektur).

**Hinweis zur Vorgeschichte**: Ein früherer Plan (17 Schritte) existierte
bereits und wurde teilweise umgesetzt (Root-Aggregator-POM, `messageId`-Feld,
Entfernung von `MessageConsumer`). Diese Arbeit basierte auf einer falschen
Planungsgrundlage und wird hier bewusst nicht fortgeführt, sondern durch die
folgenden Schritte ersetzt. Das ist eine Korrektur, kein Vergessen der
vorherigen Arbeit - im Git-Log sichtbar als eigene Commits, die auf den
alten aufsetzen und sie überschreiben, nicht als stillschweigende Änderung.

Da wenig Zeit bis zur Abgabe bleibt, sind die Schritte hier bewusst gröber
geschnitten als im ersten Plan - jeder Schritt bleibt ein Thema für einen
Commit, fasst aber mehr zusammengehörige Änderungen zusammen.

---

### Schritt 1: Root-POM zu echtem Elternprojekt umbauen

- **Ziel**: `mvn clean test` im Root baut `chat-service` und `batch-writer`
  über ein echtes Maven-Elternprojekt (PLANUNG.md 5), nicht nur einen
  Aggregator. `api-gateway`/`user-service` sind nicht mehr Teil dieses
  Builds (Spec, Abschnitt 1 und 7).
- **Änderung**: Root-`pom.xml` bekommt `spring-boot-starter-parent` als
  eigenen `<parent>`, `<modules>` nur noch `backend/chat-service` und
  `backend/batch-writer`. `chat-service/pom.xml` bekommt `<parent>` auf
  das Root-POM statt direkt auf `spring-boot-starter-parent`.
- **Test**: `mvn clean test` im Root baut genau zwei Module, beide grün.
- **Commit-Message**: `build: convert root pom to real parent project, scope to chat-service and batch-writer`

### Schritt 2: chat-service entschlackt - keine Authentifizierung, keine Datenbank

- **Ziel**: chat-service entspricht PLANUNG.md 3.1 (*"Token wird nur am
  Gateway geprüft"*) und wird reiner Validierer/Publisher, ohne eigene
  Datenbankanbindung (Spec, Abschnitt 1).
- **Änderung**: `SecurityConfig`, `oauth2-resource-server`-Abhängigkeit
  entfernt. `Message`-Entity, `MessageRepository`, `MessageMapper`,
  `GET /api/messages/{roomId}` entfernt (Lesepfad ist laut Aufgabenstellung
  nicht Teil dieser Abgabe und würde sonst mit dem von batch-writer
  verwalteten Schema kollidieren). `spring-boot-starter-data-jpa` und
  `postgresql`-Abhängigkeit aus `pom.xml` entfernt, `application.yml` ohne
  Datenquelle.
- **Test**: chat-service startet ohne Postgres- und ohne
  Keycloak-Erreichbarkeit.
- **Commit-Message**: `refactor(chat-service): remove authentication and database access, chat-service becomes publish-only`

### Schritt 3: Neuer Nachrichtenvertrag und direkte Queue

- **Ziel**: chat-service erzeugt und publiziert Nachrichten exakt nach
  Vertrag aus Spec, Abschnitt 2.
- **Änderung**: Request-/Publish-Datensatz auf `id`, `roomId`, `senderId`,
  `senderName`, `content`, `sentAt` umgestellt; `id` (UUID) und `sentAt`
  (Zeitstempel) werden beim Publizieren serverseitig erzeugt (PLANUNG.md
  3.4). Endpunkt-Pfad auf `/messages` geändert (ohne `/api`-Präfix, siehe
  S3/S4/S6-Formulierung). `RabbitMqConfig` vereinfacht: keine eigene
  `TopicExchange` mehr, Publizieren direkt auf die Queue `chat.persist`
  (Standard-Exchange, Routing-Key = Queue-Name), Begründung siehe Spec,
  Abschnitt 3.
- **Test**: Unit-Test prüft, dass `id` und `sentAt` bei jedem Aufruf gesetzt
  werden; Integrationstest (RabbitMQ-Testcontainer) prüft, dass die
  Nachricht mit allen sechs Feldern in `chat.persist` ankommt.
- **Commit-Message**: `feat(chat-service): publish messages with full contract directly to chat.persist`

### Schritt 4: Tabelle `message` per Init-Skript

- **Ziel**: Das Schema entsteht unabhängig von chat-service/batch-writer
  (Spec, Abschnitt 4.1 - kein `ddl-auto`, da `JdbcTemplate` statt JPA).
- **Änderung**: `infrastructure/postgres/init.sql` auf eine einzige Tabelle
  `message` reduziert (Spalten/Index exakt nach Spec 4.1). Die bisherigen
  `CREATE DATABASE user_db/chat_db`-Anweisungen entfallen - es gibt nur
  noch eine Datenbank (`POSTGRES_DB`), da ausser batch-writer kein Dienst
  in diesem Stack die Datenbank anfasst.
- **Test**: `docker compose exec postgres psql ... -c "\d message"` zeigt
  die erwarteten Spalten und den Index.
- **Commit-Message**: `feat(infra): create message table via postgres init script`

### Schritt 5: batch-writer-Grundgerüst

- **Ziel**: Ein eigenständiges, lauffähiges Spring-Boot-Modul existiert.
- **Änderung**: `backend/batch-writer` mit `pom.xml` (Parent = Root-POM;
  `spring-boot-starter-amqp`, `spring-boot-starter-jdbc`, `postgresql`,
  Lombok, Testcontainers für Tests - bewusst **kein**
  `spring-boot-starter-data-jpa`, siehe Spec Abschnitt 1), Package-Struktur,
  Application-Klasse, `application.yml`, `Dockerfile`. Noch keine
  Fachlogik.
- **Test**: Anwendungskontext startet fehlerfrei gegen laufende
  Infrastruktur.
- **Commit-Message**: `feat(batch-writer): scaffold Spring Boot module with JdbcTemplate`

### Schritt 6: RabbitMQ-Topologie mit Retry und DLQ

- **Ziel**: `chat.persist`, eine Retry-Queue (TTL 15 s) und `chat.dlq`
  existieren mit korrektem Dead-Letter-Routing (Spec, Abschnitt 3).
- **Änderung**: `RabbitMqConfig` in batch-writer deklariert: Queue
  `chat.persist` (durable, `x-dead-letter-exchange` zeigt auf einen
  Retry-Exchange); Retry-Exchange + Retry-Queue (durable,
  `x-message-ttl=15000`, dead-lettert nach Ablauf zurück auf
  `chat.persist`); Queue `chat.dlq` (durable). Zählung der
  Fehlversuche über den von RabbitMQ automatisch mitgeführten
  `x-death`-Header.
- **Test**: Integrationstest (RabbitMQ-Testcontainer) prüft Existenz und
  Verknüpfung aller vier Objekte über `AmqpAdmin`.
- **Commit-Message**: `feat(batch-writer): declare chat.persist retry topology with dead-letter routing to chat.dlq`

### Schritt 7: Batch-Consumer

- **Ziel**: Nachrichten werden gepuffert und gebündelt verarbeitet (Spec,
  Abschnitt 5.1), Parameter exakt 500 Nachrichten oder 200 ms.
- **Änderung**: Consumer liest aus `chat.persist` (manuelles Ack,
  Prefetch = Batch-Grösse), sammelt in einem Puffer, flusht bei 500 Stück
  oder nach 200 ms, je nachdem was zuerst eintritt.
- **Test**: Integrationstest schickt 1000 Nachrichten, prüft Batch-Grössen
  (grobe Grundlage für S3/S4, vollständiger Test folgt in Schritt 9).
- **Commit-Message**: `feat(batch-writer): batch consumer with size- and time-based flush (500/200ms)`

### Schritt 8: Bulk-Insert mit Duplikatbehandlung

- **Ziel**: Ein Batch wird in einer Transaktion gespeichert, Duplikate
  werden verworfen, nicht abgelehnt (S5).
- **Änderung**: `JdbcTemplate.batchUpdate(...)` mit
  `INSERT INTO message (...) VALUES (...) ON CONFLICT (id) DO NOTHING`.
  Nach erfolgreichem Commit: Ack für den ganzen Stapel.
- **Test**: Integrationstest reproduziert S5 exakt (dieselbe Nachricht
  zweimal direkt in `chat.persist`, nur `content_type`-Header) - genau
  eine Zeile, nichts in `chat.dlq`.
- **Commit-Message**: `feat(batch-writer): idempotent bulk insert via ON CONFLICT DO NOTHING (S5)`

### Schritt 9: Retry-Routing und Ausfallverhalten

- **Ziel**: Fehlgeschlagene Batches werden bis zu dreimal mit 15 s Abstand
  erneut versucht, danach DLQ (Spec, Abschnitt 3 und 5.3/5.4). Der Prozess
  übersteht einen Datenbankausfall ohne Absturz (S7).
- **Änderung**: Bei Fehler beim Batch-Commit: `x-death`-Zähler prüfen; bei
  weniger als 3 Versuchen Reject ohne Requeue (→ Retry-Queue → nach 15 s
  zurück zu `chat.persist`); ab 3 Versuchen Reject, der über das
  DLQ-Argument nach `chat.dlq` führt. Datenbankfehler werden abgefangen,
  nicht propagiert bis zum Absturz des Prozesses.
- **Test**: Integrationstest stoppt den Postgres-Testcontainer, sendet
  Nachrichten, startet ihn nach 15 s wieder - alle landen binnen 90 s in
  der Tabelle, keine in `chat.dlq`, der Consumer läuft durchgehend weiter
  (S7, vollständig).
- **Commit-Message**: `feat(batch-writer): retry with 15s delay before dead-lettering, survives transient database outages (S7)`

### Schritt 10: docker-compose.yml bereinigt und ergänzt

- **Ziel**: Der Stack enthält genau die Dienste dieser Abgabe, keinen
  Port ausser keinem (S2), Variablen direkt als `POSTGRES_*`/`RABBITMQ_*`.
- **Änderung**: `keycloak`, `api-gateway`, `user-service` aus
  `docker-compose.yml` entfernt. `batch-writer`-Dienst ergänzt (kein
  `ports`-Eintrag, `depends_on: postgres, rabbitmq`). `postgres`-Dienst
  nutzt `POSTGRES_USER`/`POSTGRES_PASSWORD`/`POSTGRES_DB` direkt aus der
  Umgebung, keine Umbenennung mehr. `.env.example` entsprechend bereinigt.
- **Test**: Frischer Klon, `.env` aus `.env.example`,
  `docker compose up -d --build` - alle Dienste `running`, kein
  `ports`-Eintrag in `docker compose config` (S2).
- **Commit-Message**: `chore(docker): scope compose stack to this submission, use POSTGRES_*/RABBITMQ_* directly`

### Schritt 11: README und letzter Rundumschlag

- **Ziel**: Dokumentation ist aktuell, alle acht Szenarien sind einmal
  komplett im Zusammenhang durchgespielt.
- **Änderung**: README-Tabelle "Stand" ergänzt/nachgeführt.
  `ARCHITECTURE.md` letzter Abgleich mit dem tatsächlichen Code.
- **Test**: Alle acht Szenarien S1-S8 nacheinander auf demselben Stack
  durchspielen, wie beim Lehrer-Testlauf, ohne Aufräumen dazwischen.
- **Commit-Message**: `docs: update README status table after batch-writer submission`

---

## Zuordnung Schritte → Szenarien

| Szenario | Primär abgesichert durch |
|---|---|
| S1 | Schritt 1 |
| S2 | Schritt 10 |
| S3 | Schritt 7, 8 |
| S4 | Schritt 7, 8 |
| S5 | Schritt 8 |
| S6 | Schritt 6 (Queue-Struktur), Schritt 8 (Constraint) |
| S7 | Schritt 9 |
| S8 | durchgehend, zusätzlich Schritt 11 (Gesamtprüfung) |
