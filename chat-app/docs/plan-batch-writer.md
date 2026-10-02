# Umsetzungsplan: batch-writer

Grundlage dieses Plans ist `docs/spec-batch-writer.md`. Jeder Schritt ist so
geschnitten, dass er einen eigenständigen, sinnvollen Commit ergibt: ein
klares Ziel, eine abgrenzbare Änderung, ein Test, der genau diese Änderung
absichert, und eine passende Commit-Message. Die Reihenfolge der Schritte
entspricht der vorgesehenen Git-Historie - jeder Schritt baut auf dem
vorherigen auf und hinterlässt das Projekt in einem lauffähigen Zustand.

**Hinweis zur Vorlage**: `docs/plan-chat-service.md` existiert nicht im
Repository, es konnte also nicht als Vorlage herangezogen werden. Dieser
Plan folgt stattdessen dem Format, das im Auftrag vorgegeben wurde (Ziel /
Änderung / Test / Commit-Message je Schritt).

**Hinweis zur Reihenfolge RabbitMQ-Topologie vor batch-writer-Modul**: Die
vorgegebene Reihenfolge (Topologie vor Modulanlage) wird hier so
aufgelöst, dass die vollständige Warteschlangen-Infrastruktur
(`chat.persist`, `chat.dlx`, `chat.dlq`) zunächst über chat-service -
den zu diesem Zeitpunkt einzigen bereits existierenden, lauffähigen
Dienst - angelegt und verifiziert wird. batch-writer deklariert dieselbe
Topologie später beim eigenen Start erneut (laut Spezifikation Abschnitt 3
ausdrücklich als unkritisch vorgesehen, da RabbitMQ-Deklarationen
idempotent sind). So existiert und funktioniert die Infrastruktur bereits,
bevor der Dienst gebaut wird, der sie konsumiert.

---

## 1. Root-POM (mvn clean test im Repository-Root)

### Schritt 1: Root-Aggregator-POM anlegen

- **Ziel**: `mvn clean test` lässt sich mit einem einzigen Befehl im
  Repository-Root ausführen und deckt alle bestehenden Module ab (S1).
- **Änderung**: Neue `pom.xml` im Repository-Root mit Packaging `pom` und
  `<modules>` für `backend/api-gateway`, `backend/user-service`,
  `backend/chat-service`. Kein `<parent>`-Bezug der drei Service-POMs auf
  dieses Root-POM - jeder Service bleibt wie bisher eigenständig mit
  `spring-boot-starter-parent` (Vorgabe: eigene `pom.xml` pro Service
  bleibt bestehen). Das Root-POM ist ein reiner Build-Aggregator, kein
  Abhängigkeits-/Versions-Parent.
- **Test**: `mvn clean test` im Repository-Root durchläuft den Reactor über
  alle drei Module, jedes Modul kompiliert erfolgreich (Testabdeckung
  selbst ist zu diesem Zeitpunkt noch nicht Thema dieses Schritts).
- **Commit-Message**: `build: add root aggregator pom for multi-module test run (S1)`

---

## 2. Anpassung chat-service

### Schritt 2: messageId zum Nachrichtenvertrag hinzufügen

- **Ziel**: Jede Nachricht, die künftig Richtung `chat.persist` geht, trägt
  einen eindeutigen, serverseitig erzeugten Idempotenz-Schlüssel
  (Spezifikation Abschnitt 2).
- **Änderung**: `NewMessageRequest` um das Feld `messageId` (UUID)
  erweitern. Der REST-Client setzt es nicht; `MessageService.publishNewMessage()`
  erzeugt beim Fehlen von `messageId` einen neuen UUID-Wert, bevor der
  Request an `MessageProducer.publish()` übergeben wird.
- **Test**: Unit-Test für `MessageService.publishNewMessage()` prüft, dass
  der an den Producer übergebene Request stets ein nicht-leeres `messageId`
  trägt, unabhängig davon, ob der eingehende REST-Request eines mitbringt.
- **Commit-Message**: `feat(chat-service): add server-generated messageId to outgoing requests`

### Schritt 3: Schemaänderung an der Tabelle messages

- **Ziel**: Die Tabelle `chat_db.messages` kann den Idempotenz-Schlüssel
  eindeutig speichern und unterstützt den bestehenden Lesepfad performant
  (Spezifikation Abschnitt 4).
- **Änderung**: `Message`-Entity um das Feld `messageId`
  (`@Column(nullable = false, unique = true)`) erweitern, zusätzlich einen
  Index auf (`roomId`, `createdAt`) über `@Table(indexes = ...)` ergänzen.
  `MessageService.createMessage()` setzt `messageId` beim Speichern (wird
  im nächsten Schritt ohnehin entfernt, muss aber bis dahin korrekt
  bleiben). `ddl-auto: update` bleibt in chat-service unverändert bestehen
  - chat-service bleibt Schema-Owner.
- **Test**: Integrationstest (Testcontainers-Postgres) prüft, dass die
  Spalte `message_id` und der zusammengesetzte Index nach dem Start
  existieren, und dass ein zweiter Insert mit identischem `messageId` mit
  einer Constraint-Verletzung fehlschlägt.
- **Commit-Message**: `feat(chat-service): add message_id column and composite index to messages table`

### Schritt 4: MessageConsumer entfernen, alte Queue zurückbauen

- **Ziel**: chat-service persistiert nicht mehr selbst - die Verantwortung
  für die Speicherung geht vollständig an batch-writer über (Spezifikation
  Abschnitt 1, Rahmenentscheidung 10).
- **Änderung**: Klasse `MessageConsumer` sowie die nun ungenutzte Methode
  `MessageService.createMessage()` entfernen. In `RabbitMqConfig`: die
  Queue-/Binding-Beans für die alte `chat.message.queue` entfernen, die
  Routing-Key-Konstante von `chat.message` auf `chat.persist` umbenennen
  (noch ohne zugehörige Queue-Deklaration - die folgt in Schritt 5).
  `ARCHITECTURE.md` um eine kurze Ergänzung zu Punkt 8 erweitern: chat-service
  ist ab jetzt reiner Producer.
- **Test**: chat-service startet weiterhin fehlerfrei. Ein Integrationstest
  bestätigt, dass `POST /api/messages` weiterhin `202 Accepted` liefert und
  dass dabei kein Datenbank-Insert mehr ausgelöst wird (kein Consumer mehr
  vorhanden, der das täte).
- **Commit-Message**: `refactor(chat-service): remove MessageConsumer, chat-service becomes publish-only`

---

## 3. RabbitMQ-Topologie

### Schritt 5: chat.persist, chat.dlx und chat.dlq deklarieren

- **Ziel**: Die vollständige Infrastruktur für die Nachrichtenpersistenz
  (Queue mit Dead-Letter-Weiterleitung, Dead-Letter-Exchange, DLQ) existiert
  in RabbitMQ und ist unabhängig von batch-writer bereits überprüfbar
  (Spezifikation Abschnitt 3).
- **Änderung**: In `RabbitMqConfig` (chat-service) neue Beans ergänzen:
  Queue `chat.persist` (durable, Argument `x-dead-letter-exchange: chat.dlx`),
  `FanoutExchange chat.dlx`, Queue `chat.dlq`, Binding `chat.dlx` →
  `chat.dlq`. `infrastructure/rabbitmq/README.md` aktualisieren: die
  Topologie wird vorerst von chat-service bereitgestellt und später von
  batch-writer redundant (idempotent) erneut deklariert.
- **Test**: Integrationstest (Testcontainers-RabbitMQ) prüft über
  `AmqpAdmin`, dass `chat.persist`, `chat.dlx` und `chat.dlq` nach dem
  Start existieren und die Dead-Letter-Bindung korrekt gesetzt ist. Eine
  über `POST /api/messages` gesendete Nachricht erhöht nachweisbar die
  Nachrichtenzahl in `chat.persist` um eins.
- **Commit-Message**: `feat(infra): declare chat.persist queue with dead-letter routing to chat.dlq`

---

## 4. batch-writer Modul anlegen

### Schritt 6: Leeres Spring-Boot-Modul anlegen

- **Ziel**: Ein eigenständiges, lauffähiges batch-writer-Grundgerüst
  existiert, nach demselben Muster wie die drei bestehenden Services
  (Spezifikation Abschnitt 1).
- **Änderung**: `backend/batch-writer` mit eigener `pom.xml`
  (`spring-boot-starter-parent`, kein Parent-Bezug zu anderen Services),
  Package-Struktur, Application-Klasse, `application.yml` (DB- und
  RabbitMQ-Zugangsdaten analog zu chat-service, `ddl-auto: validate` statt
  `update`, da chat-service Schema-Owner bleibt), `Dockerfile`. Noch keine
  fachliche Logik.
- **Test**: Anwendungskontext startet fehlerfrei gegen die laufende
  Infrastruktur (Smoke-Test via `@SpringBootTest`).
- **Commit-Message**: `feat(batch-writer): scaffold empty Spring Boot module`

### Schritt 7: batch-writer in den Root-Build aufnehmen

- **Ziel**: Der in Schritt 1 angelegte Root-Build deckt auch batch-writer ab.
- **Änderung**: `<module>backend/batch-writer</module>` im Root-`pom.xml`
  ergänzen.
- **Test**: `mvn clean test` im Repository-Root schliesst jetzt vier Module
  ein, alle bauen weiterhin erfolgreich.
- **Commit-Message**: `build: add batch-writer to root aggregator pom`

### Schritt 8: batch-writer in docker-compose.yml aufnehmen

- **Ziel**: batch-writer läuft als Teil der Gesamt-Architektur, ohne gegen
  die Port-Regel zu verstossen (S2).
- **Änderung**: Neuer Dienst `batch-writer` in `docker/docker-compose.yml`:
  `build.context`, Umgebungsvariablen (DB-/RabbitMQ-Zugangsdaten analog zu
  chat-service), `depends_on: postgres, rabbitmq` (nicht `keycloak`, da
  batch-writer keine Keycloak-Anbindung hat), kein `ports`-Eintrag.
- **Test**: `docker compose up -d --build` startet alle Dienste inkl.
  batch-writer fehlerfrei; `docker compose ps` zeigt für batch-writer keinen
  veröffentlichten Port.
- **Commit-Message**: `chore(docker): add batch-writer service to docker-compose.yml`

---

## 5. Datenbankzugriff

### Schritt 9: Message-Entity und Repository in batch-writer anlegen

- **Ziel**: batch-writer kann technisch gegen dieselbe Tabelle lesen und
  schreiben wie chat-service, ohne deren Schema zu verändern (Spezifikation
  Abschnitt 4).
- **Änderung**: `Message`-Entity (gleiche Struktur wie in chat-service,
  inklusive `messageId`) und `MessageRepository` (`JpaRepository`) in
  batch-writer anlegen. Noch keine Batching- oder Konsumentenlogik.
- **Test**: Integrationstest (Testcontainers-Postgres, Schema durch einen
  vorbereitenden chat-service-Start oder ein mitgeliefertes SQL-Skript
  erzeugt) bestätigt: batch-writer-Kontext startet fehlerfrei
  (`ddl-auto: validate` schlägt nicht fehl), eine über das Repository
  gespeicherte Testzeile ist lesbar.
- **Commit-Message**: `feat(batch-writer): add Message entity and repository for shared messages table`

---

## 6. Batching

### Schritt 10: Consumer mit Batch-Puffer implementieren

- **Ziel**: Nachrichten aus `chat.persist` werden gebündelt statt einzeln
  gespeichert, mit begrenzter Anzahl Transaktionen (S3, S4).
- **Änderung**: Consumer-Komponente, die aus `chat.persist` liest (manuelles
  Ack, Prefetch = Batch-Grösse) und Nachrichten in einem Batch-Puffer
  sammelt. Flush-Auslöser: Batch-Grösse erreicht (Default 50) ODER
  maximale Wartezeit überschritten (Default 2 s). Ack aller gepufferten
  Nachrichten erst nach erfolgreichem Batch-Commit.
- **Test**: Integrationstest (Testcontainers RabbitMQ + Postgres)
  veröffentlicht 1000 Testnachrichten und zählt die tatsächlich
  ausgeführten Insert-Transaktionen (z. B. über Statement-Zähler); erwartet
  werden rund 20 Transaktionen, alle 1000 Nachrichten sind binnen 60 s in
  der Tabelle, die Queue ist danach leer (S3/S4-Grundlage, grosser
  End-to-End-Test folgt in Abschnitt 10).
- **Commit-Message**: `feat(batch-writer): add batching consumer with size- and time-based flush`

---

## 7. Duplikatbehandlung

### Schritt 11: Idempotenten Batch-Insert implementieren

- **Ziel**: Dieselbe Nachricht führt nie zu mehr als einer gespeicherten
  Zeile, unabhängig davon, wie oft sie zugestellt wird (S5).
- **Änderung**: Batch-Commit aus Schritt 10 auf eine konfliktsichere
  Einfüge-Operation umstellen (Konfliktbehandlung auf `message_id`, z. B.
  äquivalent zu `INSERT ... ON CONFLICT (message_id) DO NOTHING`), nicht
  "erst prüfen, dann einfügen". Ein erkanntes Duplikat wird regulär
  bestätigt (Ack), nicht als Fehler behandelt.
- **Test**: Integrationstest reproduziert S5 exakt: dieselbe Nachricht
  (identisches `messageId`) wird zweimal direkt in `chat.persist` gelegt,
  nur mit gesetztem Header `content_type: application/json`. Erwartet:
  genau eine Zeile in der Tabelle, keine Nachricht in `chat.dlq`.
- **Commit-Message**: `feat(batch-writer): idempotent batch insert, duplicate messageId is a no-op (S5)`

---

## 8. Retry / DLQ

### Schritt 12: Format-/Validierungsfehler direkt in die DLQ leiten

- **Ziel**: Nicht verarbeitbare Nachrichten blockieren die Queue nicht und
  landen nachvollziehbar in `chat.dlq` (S8-Bezug: Nachvollziehbarkeit).
- **Änderung**: Beim Konsumieren werden Format-/Validierungsfehler (kein
  valides JSON, fehlendes Pflichtfeld, leerer `content`, ungültiges
  UUID-Format) erkannt und führen zu einem sofortigen Reject ohne Requeue
  (löst über das Dead-Letter-Argument der Queue die Weiterleitung an
  `chat.dlq` aus). Fehlergrund wird auf Error-Level geloggt.
- **Test**: Integrationstest sendet eine Nachricht mit ungültigem JSON bzw.
  fehlendem Pflichtfeld direkt in `chat.persist`; erwartet: Nachricht
  landet in `chat.dlq`, nicht in der Tabelle, kein Retry-Loop entsteht.
- **Commit-Message**: `feat(batch-writer): reject malformed messages to chat.dlq without retry`

### Schritt 13: Retry mit Backoff bei Infrastrukturfehlern

- **Ziel**: Ein vorübergehender Datenbankausfall führt nicht zu Datenverlust
  und nicht zu fälschlichem DLQ-Routing (S7).
- **Änderung**: Fehler beim Batch-Commit, die auf einen
  Infrastrukturfehler zurückgehen (z. B. Datenbankverbindung nicht
  verfügbar), führen zu Requeue mit exponentiell steigendem Backoff
  (Default: 1 s, 2 s, 4 s, 8 s, gedeckelt bei 10 s). Ein Retry-Zähler pro
  Nachricht wird mitgeführt; nach Erreichen einer Obergrenze (Default 10
  Versuche) wird zusätzlich ein Reject ohne Requeue ausgelöst (Sicherheitsnetz,
  verhindert dauerhafte Blockade bei einem wirklich kaputten Infrastruktur-
  zustand). Der Prozess selbst darf durch einen Datenbankfehler nicht
  abstürzen.
- **Test**: Integrationstest pausiert den Postgres-Testcontainer, sendet
  Nachrichten, startet Postgres nach wenigen Sekunden wieder; erwartet:
  alle Nachrichten landen letztlich in der Tabelle, keine davon in
  `chat.dlq`, der batch-writer-Prozess läuft währenddessen ohne Neustart
  weiter (kleine Vorstufe zum vollständigen S7-Test in Abschnitt 10).
- **Commit-Message**: `feat(batch-writer): retry with exponential backoff on infrastructure failures`

---

## 9. Skalierung

### Schritt 14: Mehrinstanzbetrieb verifizieren

- **Ziel**: Zwei gleichzeitig laufende batch-writer-Instanzen verarbeiten
  dieselbe Queue korrekt, ohne Nachrichten zu verlieren oder zu
  duplizieren (S6).
- **Änderung**: Keine neue Fachlogik - der Mehrinstanzbetrieb ergibt sich
  aus dem Competing-Consumers-Verhalten von RabbitMQ in Kombination mit
  dem DB-seitigen Unique-Constraint (Schritt 3) und dem idempotenten Insert
  (Schritt 11). Prefetch-Konfiguration aus Schritt 10 wird überprüft und
  bei Bedarf feinjustiert, damit keine Instanz die andere dauerhaft
  verdrängt.
- **Test**: Integrationstest mit zwei gleichzeitig laufenden
  batch-writer-Instanzen (z. B. über `docker compose up -d --build --scale
  batch-writer=2` oder zwei parallele Testkontexte) gegen 1000
  Nachrichten: alle 1000 sind gespeichert, keine Zeile ist doppelt
  vorhanden.
- **Commit-Message**: `test(batch-writer): verify concurrent instances do not duplicate messages (S6)`

---

## 10. Integrationstests

### Schritt 15: Vollständiger S3/S4-Test (Durchsatz und Transaktionsgrenze)

- **Ziel**: S3 und S4 sind als eigenständige, vollständige Tests abgedeckt,
  nicht nur als Nebeneffekt kleinerer Entwicklungstests.
- **Änderung**: Dedizierter Integrationstest: 1000 Nachrichten über den
  reellen Weg (API Gateway → chat-service → `chat.persist`) senden,
  Zeitmessung bis alle 1000 Zeilen vorhanden und die Queue leer ist
  (Grenze 60 s, S3). Zweiter Testfall im selben Testmodul: batch-writer vor
  dem Senden stoppen, 1000 Nachrichten senden, batch-writer starten,
  Transaktionsanzahl messen (Grenze 100, S4).
- **Test**: ist selbst der Test (siehe Änderung).
- **Commit-Message**: `test(batch-writer): full-scale S3/S4 integration test`

### Schritt 16: Vollständiger S7-Test (Datenbankausfall)

- **Ziel**: S7 ist als eigenständiger, vollständiger Test abgedeckt.
- **Änderung**: Dedizierter Integrationstest: Postgres-Testcontainer für
  15 s pausieren, währenddessen 300 Nachrichten senden, Postgres wieder
  starten, Zeitmessung bis alle 300 Zeilen vorhanden sind (Grenze 90 s),
  ohne den batch-writer-Prozess manuell neu zu starten.
- **Test**: ist selbst der Test (siehe Änderung).
- **Commit-Message**: `test(batch-writer): full-scale S7 integration test (database outage recovery)`

### Schritt 17: S1, S2 und S8 projektweit verifizieren und dokumentieren

- **Ziel**: Die verbleibenden, nicht rein batch-writer-internen Szenarien
  sind nachweislich erfüllt und das Ergebnis ist festgehalten.
- **Änderung**: Keine Fachlogik-Änderung. Verifikation und Dokumentation:
  `mvn clean test` im Repository-Root (S1) und `docker compose up -d --build`
  aus einem frischen Klon mit `.env` aus `.env.example` (S2) manuell
  durchlaufen; Quelltext von `backend/batch-writer` gegen `CLAUDE.md`
  prüfen (keine Streams, Kommentar über jeder Klasse/Methode, `.env` nicht
  im Repo - S8). Ergebnis in einer kurzen Checkliste in `docs/` festhalten.
- **Test**: Die drei genannten manuellen/Skript-gestützten Prüfungen gelten
  selbst als Test für S1, S2, S8.
- **Commit-Message**: `docs(batch-writer): record S1/S2/S8 acceptance verification`

---

## Zuordnung Schritte → Szenarien (Übersicht)

| Szenario | Primär abgesichert durch |
|---|---|
| S1 | Schritt 1, 7, 17 |
| S2 | Schritt 8, 17 |
| S3 | Schritt 10, 15 |
| S4 | Schritt 10, 15 |
| S5 | Schritt 11 |
| S6 | Schritt 14 |
| S7 | Schritt 13, 16 |
| S8 | Schritt 17 (fortlaufend während der gesamten Umsetzung zu beachten) |
