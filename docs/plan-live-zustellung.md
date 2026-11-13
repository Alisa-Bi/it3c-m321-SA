# Umsetzungsplan: Live-Zustellung im chat-service

## 1. Ziel und Ausgangslage

Jede Nachricht soll sofort bei allen ankommen, die im gleichen Raum verbunden sind. Dafür veröffentlicht der `chat-service` jede Nachricht zusätzlich an den Fanout-Exchange `chat.delivery`. Jede Instanz hängt eine eigene, kurzlebige Queue daran und verteilt die Nachrichten per Server-Sent Events (SSE) an ihre offenen Verbindungen (`GET /stream?roomId=…`). Nach einem Verbindungsabbruch holt der Client mit `Last-Event-ID` nach, was ihm fehlt.

**Ausgangslage (Bewertung 1):** `chat-service` nimmt `POST /messages` an und veröffentlicht nach `chat.persist`. Der `batch-writer` schreibt in PostgreSQL (Tabelle `message`). Beide Dienste, `postgres` und `rabbitmq` laufen per `docker compose`. Der `batch-writer`, `chat.persist` und `chat.dlq` bleiben unverändert.

**Verbindliche Vorgabe:** `docs/spec-live-zustellung.md`. Alle Werte (Prefetch, Queue-Länge, Ringbuffer, Heartbeat, Timeout) stehen dort und werden hier nur verwendet. Der Code des `chat-service` liegt im Repository unter `backend/chat-service/`.

## 2. Regeln für die Bearbeitung

- Die Spezifikation ist vor diesem Plan committet. Dieser Plan wird vor Schritt 1 committet und gepusht.
- Pro Schritt: Auftrag an die KI ausführen, Ergebnis lesen, Test laufen lassen, Feld «Geprüft und korrigiert» ausfüllen, dann ein einziger Commit. Ein Thema pro Commit.
- Commit-Messages sind auf Deutsch. Commits, die die KI geschrieben hat, tragen ihre `Co-Authored-By`-Zeile.
- Nach dem Push wird bestehender Text nicht mehr geändert. Einzige Ausnahme: das Feld «Geprüft und korrigiert».
- Muss umgeplant werden, bleibt der alte Text stehen. Die Änderung kommt als eigener Eintrag mit Änderungsgrund in Abschnitt 5, in einem eigenen Commit.
- Für Code gilt `CLAUDE.md`: Code englisch, Kommentare deutsch, keine Streams (auch nicht in Tests), Kommentar über jeder Klasse und Methode.
- Bestehende Tests bleiben grün. `mvn clean test` im Wurzelverzeichnis muss nach jedem Schritt laufen.

## 3. Umsetzungsschritte

### Schritt 1: Ausgangsstand dokumentieren

**Ziel:** Festhalten, was im `chat-service` und im Compose-Stack schon da ist und was fehlt.

**Warum an dieser Stelle:** Vor jeder Änderung muss klar sein, worauf wir aufbauen. Erst dann sind die folgenden Schritte begründet.

**Auftrag an die KI:**

> Lies `CLAUDE.md`, `PLANUNG.md`, `docs/spec-live-zustellung.md`, `docker-compose.yml` und den Quelltext von `backend/chat-service`. Ändere keinen bestehenden Code. Erstelle `docs/ausgangsstand-live-zustellung.md` auf Deutsch mit: vorhandenen Klassen und Endpunkten, der RabbitMQ-Konfiguration (Exchanges, Queues), den Abhängigkeiten in der `pom.xml`, den Umgebungsvariablen des Dienstes im Compose, und einer Liste, was für die Live-Zustellung noch fehlt. Prüfe ausserdem, ob `chat-service` ohne `container_name` skalierbar ist und ob irgendein Dienst Ports veröffentlicht. Schreibe nur auf, was du im Code gesehen hast, und behaupte kein Testergebnis, das du nicht ausgeführt hast.

**Test:** Ich führe selbst aus: `mvn clean test` und `docker compose up -d --build`, danach `docker compose ps` und `docker compose config`. In der Ausgabe von `docker compose config` darf `published:` nicht vorkommen. Das Dokument vergleiche ich mit dem Code.

**Commit-Message:** `docs(chat-service): Ausgangsstand für Live-Zustellung festhalten`

**Geprüft und korrigiert:**
- Das Dokument `docs/ausgangsstand-live-zustellung.md` wurde von der KI erstellt, ohne bestehenden Code zu ändern.
- `mvn clean test` ist grün. `docker compose up -d --build` startet alle vier Dienste (`postgres`, `rabbitmq`, `chat-service`, `batch-writer`). `docker compose ps` zeigt bei keinem Dienst einen veröffentlichten Host-Port, und `docker compose config` enthält kein `published:`.
- Korrigiert: nichts.

---

### Schritt 2: Nachrichten auf beiden Wegen veröffentlichen

**Ziel:** Jede gültige Nachricht geht weiterhin an `chat.persist` und zusätzlich an den Fanout-Exchange `chat.delivery`.

**Warum an dieser Stelle:** Ohne diese Veröffentlichung gibt es nichts zu verteilen. Zuerst die Quelle, dann der Empfang. Die Live-Veröffentlichung darf nicht von PostgreSQL oder vom `batch-writer` abhängen (Grundlage für S5).

**Auftrag an die KI:**

> Ergänze im `chat-service` den Fanout-Exchange `chat.delivery` (dauerhaft, nicht auto-delete, ohne Routing-Key) und veröffentliche jede gültige Nachricht zusätzlich dorthin. Der bestehende Weg über `chat.persist` bleibt unverändert. Beide Wege senden dasselbe JSON mit denselben Feldern (`id`, `roomId`, `senderId`, `senderName`, `content`, `sentAt`). Veröffentliche zuerst an `chat.persist`, danach an `chat.delivery`. Der Aufruf von `POST /messages` darf nicht auf eine Datenbank warten. Ergänze in `backend/chat-service/pom.xml` die Testabhängigkeiten: Testcontainers (JUnit-Integration und RabbitMQ-Modul) und Awaitility; das PostgreSQL-Modul kommt erst in Schritt 9. Setze in `MessageService` `sentAt` mit `Instant.now().truncatedTo(ChronoUnit.MICROS)`. Lehne einen Inhalt, der `CHAT_MAX_CONTENT_BYTES` (UTF-8-Bytes, Standard 4096, konfigurierbar) überschreitet, vor dem Veröffentlichen mit HTTP 400 ab, ohne ihn abzuschneiden. Fange AMQP-Ausnahmen beim Veröffentlichen ab und antworte mit HTTP 503; das Verhalten, wenn nur einer der beiden Wege klappt, setzt du so um, wie es die Spezifikation festlegt. Ändere nur die dafür nötigen Dateien. Ergänze Tests: `PublishBothWaysIntegrationTest` (echtes RabbitMQ per Testcontainers: eine Test-Queue wird an `chat.delivery` gebunden, `POST /messages` wird aufgerufen, beide Nachrichten aus `chat.persist` und der Test-Queue müssen identisches JSON haben), `SentAtPrecisionTest` (S10: `sentAt` hat keine Anteile unterhalb der Mikrosekunde), `MessageSizeLimitIntegrationTest` (S9: zu grosser Inhalt ergibt 400 und nichts landet in `chat.persist` oder `chat.delivery`) und `PublishFailureIntegrationTest` (ausgefallene Verbindung ergibt 503). Die bestehenden Tests müssen grün bleiben.

**Test:** `mvn -pl backend/chat-service test` (Klassen `PublishBothWaysIntegrationTest`, `SentAtPrecisionTest`, `MessageSizeLimitIntegrationTest`, `PublishFailureIntegrationTest`).

**Commit-Message:** `feat(chat-service): Nachrichten zusätzlich an chat.delivery veröffentlichen`

**Geprüft und korrigiert:**
- `mvn clean test` ist grün. Die vier neuen Testklassen laufen mit echtem RabbitMQ (Testcontainers): PublishBothWaysIntegrationTest, SentAtPrecisionTest, MessageSizeLimitIntegrationTest, PublishFailureIntegrationTest.
- Am laufenden Stack geprüft: `POST /messages` antwortet 202, ein Inhalt über 4096 Bytes antwortet 400, und die Nachricht erscheint in `chat.persist`. Das JSON dort enthält `sentAt` als Zahl mit Mikrosekunden.
- Korrigiert: Am Ergebnis der KI war keine Korrektur nötig.

---

### Schritt 3: Eigene Queue pro Instanz und Verteilerklasse

**Ziel:** Jede laufende Instanz erhält eine eigene Queue am Exchange und übergibt die Nachrichten einer Verteilerklasse, die vorerst nur protokolliert.

**Warum an dieser Stelle:** Der Exchange verteilt nur an gebundene Queues. Teilten sich die Instanzen eine Queue, bekäme jede Nachricht nur eine Instanz, und ein Client an der anderen Instanz verpasste sie.

**Auftrag an die KI:**

> Lege im `chat-service` pro Instanz eine eigene Queue an, die an `chat.delivery` gebunden ist: exklusiv, auto-delete, nicht dauerhaft, mit automatisch erzeugtem, eindeutigem Namen (kein fester Name). Starte genau einen Consumer mit genau einem Thread für diese Queue. Er übergibt jede Nachricht an eine neue Klasse `LiveDispatcher`, die sie vorerst nur protokolliert. Fehler im Dispatcher dürfen keine Wiederzustellung (Requeue) auslösen. Die bestehenden Tests müssen grün bleiben; falls ein Test den Listener nicht braucht, deaktiviere ihn dort gezielt. Ergänze einen Integrationstest mit echtem RabbitMQ: Eine Nachricht an `chat.delivery` erreicht den `LiveDispatcher`. Der Test prüft per `rabbitmqctl list_queues name consumers` im Container, dass genau eine Queue mit einem Consumer am Exchange hängt.

**Test:** `mvn -pl backend/chat-service test` (Klasse `DeliveryQueueIntegrationTest`).

**Commit-Message:** `feat(chat-service): Zustell-Queue pro Instanz und Verteilerklasse einrichten`

**Geprüft und korrigiert:**
- `mvn clean test` ist grün (DeliveryQueueIntegrationTest mit drei Tests).
- Am laufenden Stack geprüft: `rabbitmqctl list_queues` zeigt eine Queue `spring.gen-…` mit `durable=false auto_delete=true exclusive=true consumers=1`. `list_bindings` zeigt `chat.delivery → spring.gen-…`. Nach `Send-Message` steht im Log «Live message received» mit dem vollständigen JSON. Nach `docker compose stop chat-service` ist die Queue weg.
- Beobachtet: Meine erste Prüfung am Stack zeigte keine Queue, weil ich sie ausführte, bevor `chat-service` fertig gestartet war. Nach «Started ChatServiceApplication» war alles wie erwartet. Der Code musste nicht geändert werden.
- Korrigiert: Die drei Integrationstests aus Schritt 2 schalten den Listener ab (`auto-startup=false`), damit der neue Consumer ihre Test-Queues nicht stört.

---

### Schritt 4: SSE-Endpunkt mit Verbindungsverwaltung

**Ziel:** Clients können `GET /stream?roomId=…` öffnen. Der Dienst verwaltet die offenen Verbindungen sicher.

**Warum an dieser Stelle:** Der Verteiler braucht ein Ziel. Zuerst der Endpunkt und die Verwaltung, dann die eigentliche Verteilung.

**Auftrag an die KI:**

> Implementiere im `chat-service` den Endpunkt `GET /stream?roomId=…` mit `SseEmitter` und `Content-Type: text/event-stream`. Erstelle eine threadsichere Klasse `SseConnectionRegistry`, die offene Verbindungen nach `roomId` führt und Verbindungen bei Abschluss, Timeout oder Fehler entfernt. Sende in dem in der Spezifikation festgelegten Intervall einen Heartbeat als SSE-Kommentarzeile. Timeout und Heartbeat sind konfigurierbar (`application.yml`, per Umgebungsvariable überschreibbar). Fehlt `roomId` oder ist sie keine gültige UUID, antwortet der Endpunkt mit 400. Trage neue Variablen in `.env.example` ein. Verwende keine Streams. Ergänze einen Integrationstest mit einem echten HTTP-Client auf zufälligem Port (lies die Antwort mit `BodyHandlers.ofInputStream()` und einer Schleife über `BufferedReader.readLine()`).

**Test:** `mvn -pl backend/chat-service test` (Klasse `StreamEndpointIntegrationTest`). Geprüft werden Status 200, `Content-Type: text/event-stream`, 400 ohne gültige `roomId`, zwei gleichzeitige Verbindungen und das Entfernen einer getrennten Verbindung aus der Registry.

**Commit-Message:** `feat(chat-service): SSE-Endpunkt und Verbindungsverwaltung ergänzen`

**Geprüft und korrigiert:**
- Erster Lauf von `StreamEndpointIntegrationTest`: 4 von 5 Tests grün, `twoConnections_bothRegisteredAndReceiveHeartbeat` lief in ein Timeout.
- Ursache: Der Test erwartete die Zeile `: heartbeat` mit Leerzeichen, Spring schreibt Kommentare aber als `:heartbeat`. Die Heartbeats kamen an (die Verbindungen blieben offen und wurden korrekt eingetragen). Der Fehler lag im Test, nicht im Dienst.
- Korrigiert: Erwartete Zeile im Test auf `:heartbeat` geändert. Danach alle 5 Tests von `StreamEndpointIntegrationTest` grün, und `mvn clean test` endet mit BUILD SUCCESS.

---

### Schritt 5: Live-Verteilung nach Raum

**Ziel:** Eine empfangene Nachricht geht nur an die Verbindungen ihres Raums, im SSE-Format und in der Reihenfolge ihres Absenders.

**Warum an dieser Stelle:** Endpunkt und Registry stehen, der Verteiler kann nun echte Ziele bedienen.

**Auftrag an die KI:**

> Erweitere den `LiveDispatcher`: Lies aus dem JSON einer empfangenen Nachricht `id` und `roomId`, und sende sie an alle Verbindungen genau dieses Raums als SSE-Ereignis mit `id:` (die Nachrichten-ID) und `data:` (der JSON-Text der Nachricht exakt so, wie er aus der Queue kam, ohne ihn neu zu serialisieren). Jedes Ereignis endet mit einer Leerzeile. Ein Fehler beim Schreiben auf eine Verbindung entfernt nur diese Verbindung und stört die anderen nicht. Der Listener bleibt bei einem Thread, damit die Reihenfolge je Absender erhalten bleibt; halte das im Kommentar der Klasse fest. Ergänze einen Integrationstest mit echtem RabbitMQ: Zwei Clients in Raum A und einer in Raum B; 100 nummerierte Nachrichten eines Absenders an Raum A; beide Clients in A erhalten alle 100 in der Reihenfolge, der Client in B keine; die Zeilen `id:` und `data:` haben das geforderte Format. Keine Streams.

**Test:** `mvn -pl backend/chat-service test` (Klasse `RoomDeliveryIntegrationTest`).

**Commit-Message:** `feat(chat-service): Nachrichten raumgenau per SSE verteilen`

**Geprüft und korrigiert:**
- `RoomDeliveryIntegrationTest` grün (beide Tests). Am laufenden Stack geprüft: Eine Nachricht an Raum A erscheint im offenen `/stream` als Zeile `id:`, Zeile `data:` (der JSON-Text unverändert, die `id` stimmt mit dem Feld `id` im JSON überein) und Leerzeile. Dazwischen kommen `:connected` und `:heartbeat`.
- Korrigiert: Der Test-Client wertete die Leerzeile nach einer Kommentarzeile (`:heartbeat`) fälschlich als Formatfehler. Test-Client angepasst, Dienstcode unverändert.

---

### Schritt 6: Zwei-Instanzen-Test (S3)

**Ziel:** Zwei Instanzen liefern alle Nachrichten eines Raums genau einmal, in der Reihenfolge je Absender.

**Warum an dieser Stelle:** Verteilung und Raumfilter existieren, S3 ist damit vollständig prüfbar. Der Test ist eine geforderte Abgabe und soll früh stehen.

**Auftrag an die KI:**

> Schreibe einen Integrationstest für S3 mit einem gemeinsamen echten RabbitMQ und zwei getrennten Anwendungskontexten des `chat-service` (je eigener Port, je eigene Queue). An jeder Instanz hängt ein Client auf Raum A, ein dritter Client hängt auf Raum B. Über jede Instanz werden 200 Nachrichten für Raum A gesendet, mit je eigener `senderId` pro Instanz. Beide Clients in Raum A müssen alle 400 Nachrichten erhalten, jede genau einmal und je Absender in der gesendeten Reihenfolge. Der Client in Raum B erhält keine. Warte mit Zeitlimit statt mit festem Schlafen. Keine Streams.

**Test:** `mvn -pl backend/chat-service test` (Klasse `TwoInstancesDeliveryIntegrationTest`).

**Commit-Message:** `test(chat-service): Verteilung an zwei Instanzen prüfen (S3)`

**Geprüft und korrigiert:**
- `TwoInstancesDeliveryIntegrationTest` grün: Beide Clients in Raum A erhalten alle 400 Nachrichten, jede genau einmal und je Absender in Reihenfolge. Der Client in Raum B erhält nur die Abschlussmarke. `mvn clean test` grün.
- Korrigiert: Derselbe Leerzeilen-Fehler im Hilfsclient `SseTestClient`. Der erste Lauf scheiterte, weil die Korrektur noch nicht in meiner Datei war. Nach dem Ersetzen der Datei lief der Test grün.

---

### Schritt 7: Queue-Länge und Prefetch begrenzen

**Ziel:** Die Queue einer Instanz hält nie mehr als die in der Spezifikation festgelegte Anzahl Nachrichten, auch wenn die Instanz hängt.

**Warum an dieser Stelle:** Die Queue existiert und wird konsumiert. Erst jetzt lässt sich zeigen, was bei einem Stau passiert. `x-max-length` zählt nur bereite Nachrichten; unbestätigte begrenzt der Prefetch. Beide zusammen ergeben die Obergrenze.

**Auftrag an die KI:**

> Begrenze die Zustell-Queue jeder Instanz über die Queue-Argumente `x-max-length` und `x-overflow` mit den Werten aus der Spezifikation (Obergrenze der bereiten Nachrichten, Überlaufverhalten `drop-head`) und setze den Prefetch des Consumers auf den Wert aus der Spezifikation. Setze ausserdem `spring.rabbitmq.requested-heartbeat` auf den Wert aus der Spezifikation (30 Sekunden). Alle drei Queue-Werte sind konfigurierbar (`application.yml`, per Umgebungsvariable überschreibbar, Standardwerte aus der Spezifikation) und in `.env.example` eingetragen. Halte im Kommentar fest, dass wartende plus unbestätigte Nachrichten zusammen die Obergrenze aus der Spezifikation nicht überschreiten, weil es genau einen Consumer pro Queue gibt. Das Verwerfen geschieht im Broker; der Dienst protokolliert es nicht. Ergänze einen Integrationstest mit echtem RabbitMQ: Der Listener wird angehalten, 2000 Nachrichten gehen an den Exchange, die Queue enthält danach höchstens die eingestellte Länge (prüfe mit `rabbitmqctl list_queues name messages` im Container). Nach dem Fortsetzen des Listeners werden neue Nachrichten wieder zugestellt.

**Test:** `mvn -pl backend/chat-service test` (Klasse `QueueLimitIntegrationTest`).

**Commit-Message:** `feat(chat-service): Länge der Zustell-Queue und Prefetch begrenzen`

**Geprüft und korrigiert:**
- `QueueLimitIntegrationTest` grün (3 Tests): Bei hängendem Verbraucher und 2000 gesendeten Nachrichten zeigt der Broker genau 990 bereitliegende und 10 unbestätigte Nachrichten (zusammen höchstens 1000). Nach dem Weiterlaufen wird der Rückstau abgearbeitet und eine neue Nachricht zugestellt. Die Queue hat die Argumente `x-max-length` 990 und `drop-head`, der angeforderte RabbitMQ-Heartbeat ist 30 Sekunden.
- Abweichung vom Auftrag: Der Verbraucher wird nicht durch Anhalten des Listeners blockiert, sondern indem der Dispatcher im Test hängt. Eine auto-delete-Queue würde sonst mit dem letzten Consumer gelöscht. Eine blockierte Instanz behält ihre Verbindung, wie bei `docker pause` in S6.
- Korrigiert: Im ersten Lauf scheiterten zwei Tests wegen eines Fehlers im Test-Hilfscode (Array für den `rabbitmqctl`-Befehl um eine Stelle zu klein). Test-Hilfscode korrigiert, Dienstcode unverändert.

---

### Schritt 8: Abnahmeskript für S3 bis S6 am laufenden Stack

**Ziel:** Ein Skript stellt die Szenarien S3, S4, S5 und S6 am echten Compose-Stack nach und gibt gemessene und erwartete Werte aus.

**Warum an dieser Stelle:** Die Tests in `src/test` laufen im Testcontainer. Die Szenarien S4 bis S6 brauchen laufende Dienste, die gestoppt, pausiert und skaliert werden. Die Prüfung erfolgt am echten Stack, deshalb mein eigener Nachbau davon.

**Auftrag an die KI:**

> Schreibe `scripts/pruefe-live-zustellung.ps1` (PowerShell), das im Repository-Root gegen einen laufenden Stack läuft und S3 bis S6 mit den Zahlen aus der Spezifikation (Abschnitt 8) nachstellt. Der Zugriff auf die Instanzen erfolgt über `docker compose exec -T --index N chat-service` mit dem BusyBox-`wget` im Image; die Nachrichten-JSON wird per Eingabestrom in eine Datei im Container geschrieben und mit `wget --post-file` gesendet. S3: das Skript startet `--scale chat-service=2`, öffnet je einen SSE-Client pro Instanz für Raum A und einen für Raum B, sendet 200 Nachrichten über jede Instanz und prüft, dass beide Clients in Raum A alle 400 genau einmal erhalten, die Nachrichten eines Absenders in Reihenfolge, und Raum B nichts. S4: zwei Instanzen, `rabbitmqctl list_queues name messages consumers` und `rabbitmqctl list_bindings source_name destination_name` (genau zwei Queues am Exchange `chat.delivery`, je ein Consumer), dann `--scale chat-service=1`, spätestens 30 Sekunden später nur noch eine, `chat.persist` und `chat.dlq` bleiben. S5: neuer Raum, SSE-Client verbunden, `docker compose stop batch-writer postgres`, 100 Nachrichten, alle spätestens 5 Sekunden beim Client, danach `docker compose start postgres batch-writer` und Zählen mit `SELECT count(*) FROM message WHERE room_id = '<Raum>'` (erwartet 100). S6: zwei Instanzen, nur eine mit `docker pause <Containername>` anhalten, 5000 Nachrichten an die andere, Spalte `messages` von `rabbitmqctl list_queues name messages consumers` in einer Schleife innerhalb von etwa 60 Sekunden messen (darf 1000 nicht überschreiten), der Client an der anderen Instanz erhält alle 5000, danach `docker unpause` und eine neue Nachricht, die wieder zugestellt wird. Das Skript gibt pro Szenario den gemessenen Wert neben dem erwarteten aus. Zugangsdaten kommen aus `.env`. Keine Geheimnisse im Skript.

**Test:** Ich starte den Stack frisch und führe das Skript aus. Alle vier Szenarien müssen bestehen.

**Commit-Message:** `test(chat-service): Abnahmeskript für S3 bis S6 ergänzen`

**Geprüft und korrigiert:**
- Das Skript lief gegen den frisch gebauten Stack. S3 bis S6 bestehen mit allen Kriterien (S3: je Client 400 Nachrichten, jede id einmal, Reihenfolge je Absender, Raum B leer; S6: höchstens 1000 Nachrichten je Queue, alle 5000 beim Client der aktiven Instanz).
- Korrigiert: Die Vorprüfung im Container meldete fälschlich fehlende Werkzeuge, weil `command -v wget timeout` mit mehreren Namen im BusyBox-`sh` nicht wie erwartet arbeitet. Jetzt prüft eine Schleife jedes Werkzeug einzeln.
- Korrigiert: S4 lieferte «-1» statt der Queue-Zahl, weil PowerShell eine Liste mit einem Element zu einem einzelnen Wert macht. Die Funktionen geben die Liste jetzt mit `return ,$liste` zurück.
- Korrigiert: Die Zusammenfassung wurde in der Konsole abgeschnitten. Die Ausgabe erfolgt jetzt als einzelne Zeilen mit `Write-Host`.
- Korrigiert: S6 meldete die Reihenfolge je Absender als verletzt, obwohl alle 5000 Nachrichten ankamen. Ursache: zwei Absender senden gleichzeitig über getrennte Channels, und RabbitMQ garantiert die Reihenfolge nur je Channel. Die Spezifikation verlangt bei S6 nur die Anzahl. S6 nutzt jetzt einen einzelnen Absender mit 5000 Nachrichten nacheinander, und das Skript nennt bei einer Abweichung die erste Stelle und die Anzahl. Der erneute Lauf zeigte keine Abweichung, im Sendepfad liegt also kein Fehler.
- Beobachtet: Die Queue der pausierten Instanz verschwindet nach etwa 62 Sekunden, weil der Heartbeat (30 s) abläuft. Das entspricht der Spezifikation und ist kein Fehler.
- Hinweis: In jedem neuen PowerShell-Fenster ist vor dem Start `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` nötig.

---

### Schritt 9: PostgreSQL-Lesezugriff, ohne den Start davon abhängig zu machen

**Ziel:** Der `chat-service` kann gespeicherte Nachrichten lesen, startet und stellt aber auch zu, wenn die Datenbank nicht erreichbar ist.

**Warum an dieser Stelle:** Das Nachholen braucht die Datenbank. S5 verlangt aber, dass die Live-Zustellung auch ohne sie läuft. Beides muss zusammen stimmen, bevor der Replay entsteht.

**Auftrag an die KI:**

> Ergänze im `chat-service` den lesenden Zugriff auf PostgreSQL mit Spring JDBC (`JdbcTemplate`, kein JPA) und dem PostgreSQL-Treiber. Host `postgres` und Port `5432` stehen fest in `application.yml`. Benutzer, Passwort und Datenbankname kommen aus den Umgebungsvariablen `POSTGRES_USER`, `POSTGRES_PASSWORD` und `POSTGRES_DB`; trage genau diese drei im Compose beim Dienst `chat-service` ein, in der Form wie bei den anderen Diensten (`${POSTGRES_USER:-chatapp}`). Schreibe kein echtes Passwort in Dateien. Der Dienst erhält kein `depends_on: postgres`. Setze ausserdem ein Zeitlimit für die Datenbankabfrage beim Nachholen (`CHAT_SSE_REPLAY_DB_TIMEOUT`, Standard 5 Sekunden). Ergänze die Abhängigkeiten `spring-boot-starter-jdbc`, den PostgreSQL-Treiber und das Testcontainers-PostgreSQL-Modul in der `pom.xml`. Konfiguriere den Verbindungspool so, dass der Dienst auch dann startet und Live-Nachrichten verteilt, wenn die Datenbank nicht erreichbar ist (kein Verbindungsaufbau, der den Start verhindert). Erstelle ein Repository `MessageHistoryRepository`, das zu einer Nachrichten-ID die Position (`sent_at`, `id`) aus der Tabelle `message` lesen kann. Die Tabelle wird nur gelesen, nie geändert. Ergänze einen Integrationstest mit echtem PostgreSQL (Testcontainers, Schema aus einer Test-SQL-Datei analog zu `init.sql`): Eine eingefügte Zeile wird gelesen. Ein zweiter Test startet den Kontext mit unerreichbarer Datenbank und prüft, dass eine Live-Nachricht trotzdem zugestellt wird.

**Test:** `mvn -pl backend/chat-service test`. Danach Szenario S5 im Skript aus Schritt 8 erneut ausführen.

**Commit-Message:** `feat(chat-service): Lesezugriff auf PostgreSQL ohne Startabhängigkeit ergänzen`

**Geprüft und korrigiert:**
- `MessageHistoryRepositoryIntegrationTest` (2 Tests, echtes PostgreSQL) und `DatabaseUnavailableIntegrationTest` (2 Tests, unerreichbare Datenbank) sind grün. Eine eingefügte Zeile wird mit Raum und unverändertem Mikrosekunden-Zeitstempel gelesen, eine unbekannte id ergibt kein Ergebnis. Ohne Datenbank startet der Dienst, eine Live-Nachricht wird zugestellt, und eine Abfrage scheitert mit einer `DataAccessException` innerhalb des Zeitlimits. `mvn clean test` ist grün (27 Tests im `chat-service`, 2 im `batch-writer`).
- Am laufenden Stack geprüft: `docker compose up -d --build` startet `chat-service` ohne `depends_on: postgres`. S5 im Skript besteht: 100 Nachrichten beim Client, 0 s nach Sendeende, Reihenfolge ohne Abweichung, nach dem Neustart 100 gespeicherte Zeilen.
- Ergänzt über den Auftrag hinaus: Die Datenbank-Prüfung des Health-Endpunkts ist abgeschaltet (`management.health.db.enabled: false`), damit `/actuator/health` bei einem Datenbankausfall nicht auf DOWN fällt. Der Dienst arbeitet ohne Datenbank weiter.
- Beobachtet: Der erste Lauf von `mvn clean test` endete mit einem Fehler in `PublishBothWaysIntegrationTest` («Container startup failed for image rabbitmq:3.13-management-alpine») und dauerte rund 1,5 Stunden. Ursache war Docker Desktop, nicht der Code. Nach dem Aufräumen der Container lief derselbe Code in 3:28 min grün. Das Skript meldete zuerst «wget fehlt oder der Container läuft nicht», weil der Stack nicht lief. Nach `docker compose up -d --build` lief es durch.
- Korrigiert: Am Code war nichts zu korrigieren.

---

### Schritt 10: Ringbuffer der letzten Nachrichten pro Raum

**Ziel:** Jede Instanz hält pro Raum die letzten Nachrichten im Speicher, damit auch noch nicht gespeicherte Nachrichten nachgeholt werden können.

**Warum an dieser Stelle:** Der `batch-writer` schreibt in Stapeln. Eine Nachricht kann beim Nachholen noch nicht in der Datenbank stehen. Der Puffer schliesst diese Lücke und wird vor dem Replay gebraucht.

**Auftrag an die KI:**

> Erstelle eine threadsichere Klasse `RecentMessageBuffer`, die pro `roomId` die letzten N Nachrichten (N pro Raum, dazu die globale Gesamtgrenze, beide aus der Spezifikation und konfigurierbar; bei Überschreiten der Gesamtgrenze fliegt die global älteste Nachricht raus) mit `id`, `sentAt` und JSON-Text hält und beim Überschreiten die älteste verwirft. Der `LiveDispatcher` trägt jede empfangene Nachricht **zuerst** in den Puffer ein und verteilt sie **danach** an die Verbindungen; halte diese Reihenfolge im Kommentar fest und begründe sie. Der Puffer bietet einen Schnappschuss der Nachrichten eines Raums. Beachte Speichergrenzen gemäss Spezifikation. Ergänze einen Test: Bei N+10 Nachrichten bleiben genau die letzten N, parallele Zugriffe führen zu keinen Fehlern. Keine Streams.

**Test:** `mvn -pl backend/chat-service test` (Klasse `RecentMessageBufferTest`).

**Commit-Message:** `feat(chat-service): Ringbuffer für letzte Nachrichten je Raum ergänzen`

**Geprüft und korrigiert:**
- `RecentMessageBufferTest` und `LiveDispatcherBufferTest` sind grün, `mvn clean test` ist grün. Geprüft sind: bei N+10 Nachrichten bleiben genau die letzten N, die globale Grenze entfernt die insgesamt älteste Nachricht (auch aus einem anderen Raum), die Grenze pro Raum gilt unabhängig davon, ein Cursor aus einem anderen Raum wird nicht gefunden, doppelte ids werden ignoriert, der Schnappschuss ist eine Kopie, und parallele Zugriffe führen zu keinen Fehlern. Im `LiveDispatcher` steht die Nachricht schon im Puffer, wenn die Verbindungen des Raums abgefragt werden.
- Beim Lesen des Codes beachtet: `sentAt` kommt als Dezimalzahl in Sekunden. Als `double` gelesen ginge die letzte Nachkommastelle verloren. Der Dispatcher liest die Zahl deshalb als `BigDecimal` und kürzt auf Mikrosekunden, damit der Wert zu `sent_at` in der Datenbank passt. Ein Test mit dem echten JSON-Format des Jackson-Converters bestätigt das (auch für ganze Sekunden und für 999999 Mikrosekunden).
- Entscheidung ausserhalb des Auftrags: Eine Nachricht ohne lesbares `sentAt` wird weiter verteilt, aber nicht gepuffert, und es wird gewarnt. Nachrichten dieses Dienstes haben immer ein `sentAt`.
- Korrigiert: Am Ergebnis der KI war keine Korrektur nötig.

---

### Schritt 11: Nachzuholende Nachrichten bestimmen

**Ziel:** Zu einer `Last-Event-ID` die verpassten Nachrichten in der richtigen Reihenfolge und ohne Doppelte bestimmen (nur Lesen, noch kein Senden).

**Warum an dieser Stelle:** Reihenfolge und Cursor sind die schwierigste Logik. Sie wird zuerst isoliert und getestet, bevor sie mit dem Live-Betrieb verbunden wird.

**Auftrag an die KI:**

> Erstelle eine Klasse `ReplayService` mit einer Methode, die zu `roomId` und `Last-Event-ID` die verpassten Nachrichten liefert. Die Position der `Last-Event-ID` wird zuerst in der Datenbank gesucht, danach im Ringbuffer (die Nachricht kann noch nicht gespeichert sein). Wird sie nirgends gefunden, gilt das in der Spezifikation festgelegte Verhalten. Die Datenbank liefert alle Nachrichten des Raums nach dieser Position, sortiert nach `sent_at` und bei Gleichstand nach `id` (Zeilenvergleich `(sent_at, id) >` Position). UUIDs werden nicht als Zeitreihenfolge verglichen. Aus dem Ringbuffer kommen die Einträge nach derselben Position. Beide Mengen werden zusammengeführt, Doppelte werden anhand der `id` entfernt, das Ergebnis wird nach `(sent_at, id)` sortiert. `sentAt` ist bereits beim Erzeugen auf Mikrosekunden gekürzt; vergleiche deshalb direkt. Bei einem Datenbankfehler verhält sich der Dienst wie in der Spezifikation beschrieben. Das JSON der nachgeholten Nachrichten aus der Datenbank muss dieselben Felder haben wie das aus der Queue. Ergänze Tests mit echtem PostgreSQL in `ReplayServiceTest`: Nachrichten mit gleichem `sent_at`, Cursor nur im Ringbuffer, unbekannter Cursor, Cursor aus anderem Raum, Datenbankfehler. Ergänze zusätzlich `ReplayJsonFormatIntegrationTest` (S11): Eine Nachricht wird veröffentlicht und aus PostgreSQL gelesen, aus der Zeile entsteht ein `NewMessageRequest`, der mit demselben Jackson-Converter serialisiert wird wie beim Veröffentlichen; die beiden JSON-Texte müssen identisch sein. Keine Streams.

**Test:** `mvn -pl backend/chat-service test` (Klassen `ReplayServiceTest` und `ReplayJsonFormatIntegrationTest`).

**Commit-Message:** `feat(chat-service): verpasste Nachrichten anhand der Last-Event-ID bestimmen`

**Geprüft und korrigiert:**
- `ReplayServiceTest` und `ReplayJsonFormatIntegrationTest` sind grün, `mvn clean test` ist grün. Geprüft sind: nur Nachrichten nach dem Cursor in der richtigen Reihenfolge, Cursor als letzte Nachricht ergibt eine leere Liste, Nachrichten anderer Räume fehlen, gleicher Zeitstempel mit `id` als Tie-Breaker, Cursor nur im Ringbuffer, Zusammenführen ohne Doppelte, unbekannter Cursor (409), Cursor eines anderen Raums in der Datenbank (400), Cursor nur im Ringbuffer eines anderen Raums (409) und Datenbank nicht erreichbar bei vorhandenem Cursor im Ringbuffer (503). S11 besteht: Eine über `POST /messages` veröffentlichte Nachricht (Umlaute, Anführungszeichen, Backslash, HTML-Zeichen, Emoji) ergibt nach dem Speichern und Nachholen denselben JSON-Text.
- Beim Lesen des Codes beachtet: `java.util.UUID.compareTo` rechnet mit Vorzeichen und würde `id`s ab `8` vor `id`s von `0` bis `7` sortieren. PostgreSQL vergleicht `uuid` Byte für Byte ohne Vorzeichen. Der `ReplayService` vergleicht deshalb die beiden 64-Bit-Hälften mit `Long.compareUnsigned`. Ein Test mit `id`s `0…01`, `7fff…`, `8000…`, `c000…` und `ffff…` bei gleichem Zeitstempel belegt, dass Datenbank und Ringbuffer dieselbe Reihenfolge ergeben.
- Entscheidung ausserhalb des Auftrags: Der Dienst bietet zwei Schritte an (`resolveCursor` und `findMissed`), weil Schritt 12 den Cursor vor dem Senden von HTTP 200 prüfen muss. `missedMessages` ruft beide nacheinander auf. Bei einer Nachricht in Datenbank und Ringbuffer bleibt der ursprüngliche JSON-Text aus dem Ringbuffer.
- Bekannte Grenze: Die Abfrage hat keine Obergrenze für die Zahl der Nachrichten. Ein Client, der sehr lange weg war, erhält alles nach dem Cursor. Eine Obergrenze würde Nachrichten stillschweigend weglassen, deshalb wurde sie nicht eingebaut.
- Korrigiert: Am Ergebnis der KI war keine Korrektur nötig.

---

### Schritt 12: Übergang vom Nachholen zum Live-Betrieb

**Ziel:** Beim Verbinden mit `Last-Event-ID` erhält der Client erst die verpassten, dann die Live-Nachrichten, ohne Lücke und ohne Doppelte.

**Warum an dieser Stelle:** Nachholen und Live-Betrieb laufen gleichzeitig. Erst jetzt sind beide Teile vorhanden und können sicher verbunden werden.

**Auftrag an die KI:**

> Erweitere den Endpunkt `GET /stream`: Ohne `Last-Event-ID` läuft die Verbindung direkt im Live-Modus. Mit dem Header gilt diese Reihenfolge: (1) die Verbindung in der Registry anmelden und eintreffende Live-Nachrichten dieser Verbindung zwischenspeichern, (2) den Cursor prüfen; bei einem Fehler die Verbindung abmelden und den Status aus der Spezifikation (400, 409 oder 503) zurückgeben, nur bei Erfolg HTTP 200 senden, (3) den Schnappschuss des Ringbuffers und die Datenbankabfrage über den `ReplayService` ausführen, (4) die verpassten Nachrichten senden, (5) die zwischengespeicherten Live-Nachrichten senden und dabei Nachrichten überspringen, deren `id` schon gesendet wurde, (6) in den normalen Live-Modus wechseln. Begrenze den Zwischenspeicher pro Verbindung mit `CHAT_SSE_REPLAY_BUFFER_MAX` (Standard 1000); bei Überschreitung wird die Verbindung beendet. Fällt die Datenbank erst nach dem Senden von HTTP 200 aus, beendet der Server die Verbindung, so wie es die Spezifikation in §6.6 festlegt. Begründe im Kommentar, warum die Reihenfolge (1) vor (3) und im Dispatcher «Puffer vor Verteilung» keine Lücke lässt. Schreibzugriffe auf denselben `SseEmitter` aus Live-Pfad und Nachhol-Pfad werden pro Verbindung serialisiert. Ergänze einen Test, der während des Nachholens (die Datenbankabfrage wird künstlich verzögert) zehn Live-Nachrichten einspeist: Keine geht verloren, keine erscheint doppelt, die Reihenfolge stimmt, danach funktioniert die Live-Zustellung. Keine Streams.

**Test:** `mvn -pl backend/chat-service test` (Klasse `ReplayHandoverTest`).

**Commit-Message:** `feat(chat-service): Nachholen und Live-Zustellung ohne Lücke verbinden`

**Geprüft und korrigiert:**
-

---

### Schritt 13: Neuverbindungs-Test (S7)

**Ziel:** S7 ist als Test in `src/test` und im Abnahmeskript nachgestellt.

**Warum an dieser Stelle:** Alle Teile stehen, der Ablauf aus S7 lässt sich jetzt von Anfang bis Ende prüfen. Der Test ist eine geforderte Abgabe.

**Auftrag an die KI:**

> Schreibe einen Integrationstest für S7 mit echtem RabbitMQ und echtem PostgreSQL: Ein Client empfängt eine Nachricht und trennt die Verbindung. Danach werden 50 weitere Nachrichten gesendet. Weil in diesem Test kein `batch-writer` läuft, schreibt der Test die 50 Zeilen selbst per SQL in die Tabelle `message`, so wie es der `batch-writer` täte. Nach einer Wartezeit verbindet sich der Client mit `Last-Event-ID` der ersten Nachricht erneut. Er erhält genau die 50 fehlenden Nachrichten in der Reihenfolge, keine doppelt, und danach eine neu gesendete Nachricht live. Eine zweite Variante lässt die Datenbankzeilen weg (Nachrichten nur im Ringbuffer). Ergänze im Skript `scripts/pruefe-live-zustellung.ps1` das Szenario S7 mit 10 Sekunden Wartezeit am echten Stack. Keine Streams.

**Test:** `mvn -pl backend/chat-service test` (Klasse `ReconnectIntegrationTest`) und S7 im Skript.

**Commit-Message:** `test(chat-service): Neuverbindung mit Last-Event-ID prüfen (S7)`

**Geprüft und korrigiert:**
-

---

### Schritt 14: Dokumentation, README und Abnahme

**Ziel:** Der Stand ist dokumentiert, alle acht Szenarien sind gegen einen frischen Klon gemessen, und der Tag kann gesetzt werden.

**Warum am Schluss:** Dokumentation und Stand-Tabelle sollen den tatsächlich erreichten Zustand zeigen, und die Abnahme gilt für den Gesamtstand.

**Auftrag an die KI:**

> Aktualisiere die README-Tabelle «Stand» und ergänze eine kurze Beschreibung des Endpunkts `GET /stream`, des Replay-Verhaltens, der Konfigurationsvariablen und der bekannten Grenzen (zum Beispiel: Ein Verwerfen durch `drop-head` merkt ein verbundener Client nicht, solange er die Verbindung hält). Passe die Beschreibung im `pom.xml` des `chat-service` und die Javadoc in `NewMessageRequest` und `MessageService` an: Sie dürfen nicht mehr behaupten, der Dienst berühre keine Datenbank; beschreibe den lesenden Zugriff für das Nachholen. Prüfe `docs/` und `.env.example` auf Vollständigkeit. Prüfe den Quelltext von `backend/chat-service` (inklusive Tests) auf die Regeln aus `CLAUDE.md`: keine Java-Streams, Kommentar über jeder Klasse und Methode. Prüfe, dass `.env` nicht im Repository liegt. Erstelle keine zusätzlichen Funktionen. Liste für S1 bis S11 auf, mit welchem Befehl oder Test das Szenario geprüft wird. Gib kein Szenario als bestanden an, das nicht ausgeführt wurde.

**Test:** Frischer Klon in ein leeres Verzeichnis, `.env` aus `.env.example`, `docker compose up -d --build`, `mvn clean test` im Root, dann `scripts/pruefe-live-zustellung.ps1` für S3 bis S7. Für S8: Suche nach `.stream(`, `Stream<`, `.map(`, `.filter(` und `.collect(` im Verzeichnis `backend/chat-service/src` einschliesslich der Tests (keine Treffer) und `git ls-files .env` (muss leer sein). Für S9 bis S11 die jeweiligen Testklassen mit `mvn -pl backend/chat-service test -Dtest=<Klasse>`. Für S2: `docker compose config` ohne `published:`.

**Commit-Message:** `docs(chat-service): Stand, Endpunkt und Grenzen der Live-Zustellung beschreiben`

**Geprüft und korrigiert:**
-

---

## 4. Abschluss

Erst wenn alle Schritte committet und die Szenarien gemessen sind, wird der Tag gesetzt und gepusht:

```bash
git tag bewertung-2
git push origin bewertung-2
```

## 5. Änderungen am Plan

Hier steht jede spätere Umplanung als eigener Eintrag mit Datum, Änderung und Grund. Der ursprüngliche Text oben bleibt dabei unverändert.

(noch keine)
