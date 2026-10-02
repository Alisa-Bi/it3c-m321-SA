# Spezifikation: batch-writer

Dieses Dokument beschreibt den Dienst `batch-writer` vollständig genug, um
darauf `docs/plan-batch-writer.md` (den Umsetzungsplan) aufzubauen. Es
enthält keinen Code und keinen Umsetzungsplan - nur das fachliche und
technische Verhalten, das der Dienst zeigen muss.

## 0. Grundlage und Rahmenentscheidungen

Folgende Entscheidungen sind bereits getroffen und bilden die Grundlage
dieser Spezifikation:

| # | Entscheidung | Herkunft |
|---|---|---|
| 1 | PostgreSQL bleibt die zentrale Datenbank | vorgegeben |
| 2 | RabbitMQ wird verwendet | vorgegeben |
| 3 | Queue `chat.persist`, DLQ `chat.dlq` | vorgegeben |
| 4 | API Gateway bleibt Bestandteil der Architektur | vorgegeben |
| 5 | Docker Compose bleibt bestehen | vorgegeben |
| 6 | `batch-writer` wird als eigener Microservice implementiert | vorgegeben |
| 7 | Jeder Service (inkl. `batch-writer`) hat eine eigene, eigenständige `pom.xml` | vorgegeben |
| 8 | Datenhoheit: `chat-service` bleibt fachlicher Owner der Nachrichtendaten, `batch-writer` ist reiner Persistenz-Ausführer | aus Entscheidungsgrundlage übernommen |
| 9 | Zieldatenbank: geteilte `chat_db`, bestehende Tabelle `messages` | aus Entscheidungsgrundlage übernommen |
| 10 | `MessageConsumer` im chat-service wird entfernt; chat-service produziert nur noch | aus Entscheidungsgrundlage übernommen |
| 11 | Nachrichtenformat wird um `messageId` (UUID) erweitert | aus Entscheidungsgrundlage übernommen |
| 12 | Idempotenz-Schlüssel ist `messageId`, durchgesetzt per UNIQUE-Constraint in der DB | aus Entscheidungsgrundlage übernommen |
| 13 | RabbitMQ-Topologie: `chat.persist` am bestehenden `chat.exchange`, eigener Dead-Letter-Exchange `chat.dlx` gebunden an `chat.dlq` | aus Entscheidungsgrundlage übernommen |
| 14 | Retry vs. DLQ wird nach Fehlertyp unterschieden (Infrastruktur → Retry, Format/Validierung → DLQ) | aus Entscheidungsgrundlage übernommen |
| 15 | Root-Build: schlanker Aggregator-POM im Repo-Root für `mvn clean test`, kein gemeinsamer Parent | aus Entscheidungsgrundlage übernommen |
| 16 | Batching: kombiniert größen- und zeitbasiert | aus Entscheidungsgrundlage übernommen |

**Verbleibende Annahme, nicht final bestätigt**: S3/S4/S6 sprechen von
`POST /messages`, S5 explizit von einem direkten Eingriff in `chat.persist`.
Diese Spezifikation geht davon aus, dass S3/S4/S6 den regulären,
authentifizierten Weg über API Gateway → chat-service meinen (sonst hätte
S5 die Abweichung nicht eigens betont). `batch-writer` selbst ist von dieser
Frage nicht betroffen, da er nie über REST angesprochen wird - er
konsumiert ausschließlich aus `chat.persist`. Diese Annahme wird hier nur
der Vollständigkeit halber festgehalten.

---

## 1. Zweck und Abgrenzung

**Zweck**: `batch-writer` konsumiert Nachrichten aus der Queue
`chat.persist` und speichert sie gebündelt (in Batches) dauerhaft in der
Tabelle `messages` der Datenbank `chat_db`. Er entkoppelt die
Schreib-Last auf PostgreSQL von der Annahme-Geschwindigkeit des
chat-service und reduziert die Anzahl Datenbank-Transaktionen durch
Batching.

**Abgrenzung - was `batch-writer` NICHT tut**:

- Kein öffentlicher REST-Endpunkt, kein nach außen veröffentlichter Port.
- Keine Keycloak-/JWT-Anbindung (kein eingehender HTTP-Traffic, der
  geschützt werden müsste).
- Kein WebSocket-Broadcast an Chat-Teilnehmer (bleibt, falls künftig
  gewünscht, Aufgabe von chat-service).
- Keine fachliche Validierung über Formatprüfung hinaus - z. B. keine
  Prüfung, ob `senderId` tatsächlich Mitglied des Chatraums ist. Das bleibt
  Aufgabe von chat-service, bevor eine Nachricht überhaupt publiziert wird.
- Kein Lesezugriff/keine API für andere Dienste. `GET /api/messages/{roomId}`
  bleibt ausschließlich bei chat-service.
- Keine eigene Datenbank - schreibt in die von chat-service betriebene
  `chat_db` (siehe Abschnitt 4, Schemaverwaltung).
- Keine automatische Wiederverarbeitung oder Bereinigung der DLQ.

---

## 2. Nachrichtenvertrag

**Transport**: JSON über AMQP. Header `content_type: application/json` wird
gesetzt (das ist laut Vorgabe für S5 die einzige verlässlich gesetzte
Eigenschaft bei direktem Queue-Zugriff).

**Felder**:

| Feld | Typ | Pflicht | Bedeutung |
|---|---|---|---|
| `messageId` | UUID (String) | ja | Fachlicher Idempotenz-Schlüssel. Wird von chat-service beim Publizieren erzeugt, bevor die Nachricht an RabbitMQ geht. Identisch bei jedem Zustellversuch derselben Sendeabsicht. |
| `roomId` | UUID (String) | ja | Ziel-Chatraum. |
| `senderId` | UUID (String) | ja | Absender. |
| `content` | String | ja, nicht leer | Nachrichtentext. |

Dieses Feldset ist eine Erweiterung des bereits bestehenden
`NewMessageRequest` um `messageId`. chat-service muss vor Inbetriebnahme
von `batch-writer` entsprechend angepasst werden (Folgearbeit, nicht Teil
dieser Spezifikation, siehe `docs/plan-batch-writer.md`).

**Warum `messageId` im JSON-Body liegt, nicht als AMQP-Property**: S5
garantiert beim direkten Einlegen in `chat.persist` ausschließlich den
Header `content_type`. Ein selbst gesetzter `message-id`-Header ist in
diesem Testpfad nicht verlässlich vorhanden. Ein Feld im Nachrichtenkörper
ist deshalb die robustere Wahl.

**Verhalten bei Vertragsverletzung** (kein valides JSON, fehlendes
Pflichtfeld, leerer `content`, ungültiges UUID-Format): Die Nachricht gilt
als dauerhaft nicht verarbeitbar und wird ohne Retry-Versuch direkt an
`chat.dlq` weitergereicht (siehe Abschnitt 7).

---

## 3. RabbitMQ-Queues

**Exchange**: weiterhin `chat.exchange` (bestehender `TopicExchange`).

**Routing-Key**: `chat.persist` (neu, ersetzt den bisherigen
`chat.message`-Routing-Key für den Persistenzpfad).

**Queue `chat.persist`**:
- durable: `true`
- Consumer-Acknowledgment: manuell (kein Auto-Ack)
- Queue-Argument `x-dead-letter-exchange`: `chat.dlx`
- Kein nachrichtenweites TTL (Retry erfolgt über Requeue mit Backoff, nicht
  über TTL-Ablauf, siehe Abschnitt 6)

**Dead-Letter-Exchange `chat.dlx`** (neu):
- Typ: Fanout (keine weitere Verzweigung nötig, einzige Aufgabe ist die
  Weiterleitung an `chat.dlq`)
- Gebunden an genau eine Queue: `chat.dlq`

**Queue `chat.dlq`**:
- durable: `true`
- Keine automatische Weiterverarbeitung. Nachrichten bleiben bis zur
  manuellen Prüfung liegen (siehe Abgrenzung, Abschnitt 1).

**Deklarationsverantwortung**: `batch-writer` deklariert alle vier Objekte
(`chat.exchange`-Referenz, `chat.persist`, `chat.dlx`, `chat.dlq`) beim
Start als eigene Konfiguration. chat-service deklariert weiterhin
`chat.exchange` für den Produce-Vorgang, aber keine eigene
Nachrichten-Queue mehr (der bisherige `MessageConsumer` entfällt,
Entscheidung 10 in Abschnitt 0). RabbitMQ-Deklarationen sind idempotent;
eine doppelte, inhaltlich identische Deklaration durch zwei Dienste ist
unkritisch.

**Konsumentenkonfiguration**: Prefetch-Count = Batch-Grösse (siehe
Abschnitt 10), damit pro Instanz höchstens ein Batch gleichzeitig "in
Arbeit" ist.

---

## 4. Datenmodell

**Zieltabelle**: `chat_db.messages` (bestehende Tabelle aus chat-service,
wird weiterverwendet, nicht neu angelegt).

**Schemaänderung**: neue Spalte `message_id` (UUID), `NOT NULL`, `UNIQUE`.

Bestehende Spalten (`id`, `room_id`, `sender_id`, `content`, `created_at`,
`status`) bleiben unverändert. `id` bleibt der technische, intern von der
Datenbank vergebene Primärschlüssel; `message_id` ist der fachliche,
von außen (chat-service) vorgegebene Idempotenz-Schlüssel. Beides sind
UUIDs, aber unterschiedliche Konzepte - diese Unterscheidung muss in der
Implementierung klar benannt werden.

**Neue Indizes**:
- `UNIQUE INDEX` auf `message_id` (erzwingt die Idempotenz aus Abschnitt 5
  auf Datenbankebene, nicht nur in der Anwendungslogik).
- Index auf (`room_id`, `created_at`) für den Lesepfad von
  `GET /api/messages/{roomId}` - fehlt aktuell bereits unabhängig von
  `batch-writer`, wird hier nachgezogen, da ohnehin eine Schemaänderung
  ansteht.

**Schemaverwaltung - wichtige Klärung**: Zwei Dienste dürfen nicht
gleichzeitig per `ddl-auto: update` gegeneinander um dasselbe Schema
konkurrieren. Da chat-service laut Entscheidung 8 (Abschnitt 0) der
fachliche Owner bleibt, verwaltet **ausschließlich chat-service** das
Schema der Tabelle `messages` (inklusive der neuen Spalte `message_id` und
der neuen Indizes). `batch-writer` verbindet sich mit `ddl-auto: validate`
(oder `none`) gegen dasselbe Schema - er liest/schreibt Daten, verändert
aber nie die Tabellenstruktur. Diese Reihenfolge ist für
`docs/plan-batch-writer.md` relevant: das Schema muss vor dem ersten Start
von `batch-writer` bereits existieren.

---

## 5. Duplikatbehandlung

**Mechanismus**: Einfügen mit Konfliktbehandlung auf `message_id`
(äquivalent zu `INSERT ... ON CONFLICT (message_id) DO NOTHING`), nicht
"erst prüfen, dann einfügen" - letzteres ist unter Nebenläufigkeit (siehe
Abschnitt 9) eine Race Condition und keine verlässliche Garantie.

**Verhalten bei erkanntem Duplikat**: Die Nachricht wird als "bereits
vorhanden" behandelt, ganz normal bestätigt (Ack), **nicht** als Fehler
gewertet und **nicht** in `chat.dlq` verschoben. Ein Protokolleintrag auf
Info-Level ist zulässig, aber kein Error-Level - ein Duplikat ist laut
Aufgabenstellung (S5) ein erwarteter, zu behandelnder Normalfall, kein
Fehlerfall.

**Abgrenzung**: Ein Duplikat (gleiche `message_id`) ist strikt zu
unterscheiden von zwei inhaltlich gleichlautenden, aber eigenständigen
Nachrichten (unterschiedliche `message_id`) - letztere werden beide
gespeichert.

---

## 6. Retry-Verhalten

**Grundprinzip**: Unterscheidung nach Fehlerursache.

- **Infrastrukturfehler** (z. B. Datenbank nicht erreichbar,
  Verbindungs-Timeout beim Commit eines Batches): Die betroffenen
  Nachrichten werden **nicht** bestätigt und mit Requeue zurück an
  `chat.persist` gegeben. Sie werden später erneut zugestellt.
- **Format-/Validierungsfehler** (siehe Abschnitt 2): sofortiger Reject
  **ohne** Requeue - das löst über das Dead-Letter-Exchange-Argument der
  Queue die automatische Weiterleitung an `chat.dlq` aus (kein
  Infrastrukturfehler, kein Grund für einen erneuten Versuch).

**Backoff bei Infrastrukturfehlern**: Ohne Verzögerung würde eine
dauerhaft nicht erreichbare Datenbank zu einer Dauerschleife aus
Zustellung-Fehlschlag-Requeue führen ("busy loop"). Deshalb gilt ein
exponentiell steigender Backoff zwischen Wiederholungsversuchen, gedeckelt
bei einer Obergrenze (konkrete Werte siehe Abschnitt 10,
Konfiguration). Der genaue technische Mechanismus (z. B. verzögertes
Requeue über eine Retry-Queue mit TTL vs. In-Memory-Wartezeit vor dem
Reject) ist eine Umsetzungsentscheidung und gehört in
`docs/plan-batch-writer.md`.

**Sicherheitsnetz-Obergrenze**: Ein Retry-Zähler wird pro Nachricht
mitgeführt. Ab einer konfigurierbaren Obergrenze (Abschnitt 10) wird die
Nachricht ebenfalls nach `chat.dlq` verschoben, selbst wenn der
ursprüngliche Fehler ein Infrastrukturfehler war - das verhindert, dass
eine dauerhaft gestörte Datenbank `chat.persist` unbegrenzt blockiert. Die
konkrete Obergrenze ist so zu wählen, dass ein kurzer, vorübergehender
Ausfall (siehe S7, 15 Sekunden) sie nicht erreicht.

---

## 7. DLQ-Verhalten

**Auslöser**:
1. Format-/Validierungsfehler (Abschnitt 2).
2. Überschreiten der Retry-Obergrenze bei anhaltenden Infrastrukturfehlern
   (Abschnitt 6).

**Kein Auslöser**: Duplikate (Abschnitt 5) - das ist die wichtigste
Abgrenzung, da S5 explizit verlangt, dass im Duplikatfall nichts in
`chat.dlq` landet.

**Mechanismus**: Technisch über Reject ohne Requeue in Kombination mit dem
`x-dead-letter-exchange`-Argument der Queue `chat.persist` - RabbitMQ
übernimmt das Routing an `chat.dlx` → `chat.dlq` automatisch, kein
manuelles Publizieren durch `batch-writer` nötig.

**Nach dem Verschieben**: Keine automatische Wiederverarbeitung, kein
automatisches Löschen. Nachrichten bleiben in `chat.dlq`, bis sie manuell
geprüft werden (Abgrenzung, Abschnitt 1). Beim Verschieben wird ein
Protokolleintrag auf Error-Level mit Grund (Format-Fehler vs.
Retry-Obergrenze erreicht) erzeugt.

---

## 8. Verhalten bei Datenbankausfall

**Erkennung**: Eine JDBC-/JPA-Ausnahme beim Versuch, einen Batch zu
committen (z. B. Verbindung abgelehnt, Timeout).

**Reaktion**: Der betroffene Batch wird nicht bestätigt; alle enthaltenen
Nachrichten werden gemäß Abschnitt 6 (Infrastrukturfehler) mit Requeue und
Backoff behandelt.

**Prozessüberleben**: Der `batch-writer`-Prozess selbst darf durch einen
Datenbankausfall **nicht** abstürzen oder sich beenden. Ein nicht
erreichbarer Datenbank-Server ist ein erwarteter, zu behandelnder
Zwischenzustand, kein fataler Fehler. Das deckt sich mit der Vorgabe aus
S7, dass kein manueller Neustart nötig sein darf.

**Zeitbudget (bezogen auf S7)**: Nach einem 15-Sekunden-Ausfall müssen 300
Nachrichten binnen höchstens 90 Sekunden gespeichert sein. Die
Backoff-Parameter (Abschnitt 10) sind so gewählt, dass die Summe der
Wartezeiten während eines 15-Sekunden-Ausfalls deutlich unter der
Sicherheitsnetz-Obergrenze aus Abschnitt 6 bleibt - ein kurzer Ausfall darf
keine Nachricht fälschlich in Richtung DLQ drängen.

**Kein Datenverlust**: Da `chat.persist` durable ist und eine Nachricht
erst nach erfolgreichem Commit bestätigt wird, bleiben alle betroffenen
Nachrichten während des gesamten Ausfalls sicher in der Queue.

---

## 9. Verhalten bei mehreren batch-writer Instanzen

**Grundprinzip**: RabbitMQ verteilt die Nachrichten einer Queue
automatisch auf mehrere verbundene Consumer (Competing-Consumers-Muster).
Dafür ist in `batch-writer` selbst keine zusätzliche Verteil-Logik nötig.

**Was zusätzlich sichergestellt werden muss**: Die Eindeutigkeits-Garantie
aus Abschnitt 5 muss auch unter echter Nebenläufigkeit halten. Zwei
Instanzen, die "gleichzeitig" prüfen würden, ob eine `message_id` schon
existiert, könnten beide "nein" sehen und doppelt einfügen - eine
klassische Race Condition. Deshalb ist die Durchsetzung über den
UNIQUE-Constraint auf Datenbankebene (Abschnitt 4) zwingend, nicht nur
eine Anwendungsprüfung.

**Prefetch**: Jede Instanz begrenzt ihre gleichzeitig unbestätigten
Nachrichten auf die konfigurierte Batch-Grösse (Abschnitt 10), damit keine
Instanz der anderen dauerhaft alle Nachrichten wegnimmt. Für die reine
Abnahme (S6: "alle da, keine doppelt") ist das nicht zwingend nötig, für
eine sinnvolle Lastverteilung im Betrieb aber Teil dieser Spezifikation.

**Skalierungsgrenze**: Diese Spezifikation legt keine Obergrenze für die
Anzahl gleichzeitiger Instanzen fest - das beschriebene Muster ist
grundsätzlich horizontal skalierbar.

---

## 10. Konfiguration

| Schlüssel (konzeptionell) | Bedeutung | Empfohlener Default | Bezug |
|---|---|---|---|
| Datenbank-URL | Verbindung zu `chat_db` (geteilt mit chat-service) | `jdbc:postgresql://postgres:5432/chat_db` | Abschnitt 4 |
| Datenbank-Zugangsdaten | wie bei den bestehenden Services | aus `DB_USERNAME`/`DB_PASSWORD` | bestehend |
| Schema-Modus | verhindert Schemakonflikt mit chat-service | `validate` (nicht `update`) | Abschnitt 4 |
| RabbitMQ-Host/Port/Zugangsdaten | wie bei chat-service | aus `RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` | bestehend |
| Batch-Grösse | Anzahl Nachrichten pro Transaktion, bevor regulär committet wird | 50 | Abschnitt 6, 9 |
| Maximale Wartezeit vor Zwangs-Flush | verhindert, dass ein unvollständiger Batch unbegrenzt im Puffer hängt | 2 Sekunden | Abschnitt 6 (S7-Bezug) |
| Prefetch-Count | gleichzeitig unbestätigte Nachrichten pro Instanz | = Batch-Grösse (50) | Abschnitt 9 |
| Retry-Obergrenze | Anzahl Versuche, bevor ein Infrastrukturfehler zusätzlich in die DLQ geht | 10 | Abschnitt 6 |
| Backoff, initial | erste Wartezeit vor erneutem Versuch | 1 Sekunde | Abschnitt 6 |
| Backoff, Obergrenze | maximale Wartezeit zwischen Versuchen | 10 Sekunden | Abschnitt 6 |

**Begründung der Zahlenwerte**:
- Batch-Grösse 50 → bei 1000 Nachrichten (S3/S4) entstehen rechnerisch 20
  Transaktionen, deutlich unter der in S4 geforderten Obergrenze von 100.
- Backoff 1s/2s/4s/8s (exponentiell, Deckel 10s) → die Summe der
  Wartezeiten während eines 15-Sekunden-Ausfalls (S7) liegt bei rund 4-5
  Versuchen, weit unter der Retry-Obergrenze von 10 - ein kurzer Ausfall
  löst also kein fälschliches DLQ-Routing aus.
- Maximale Wartezeit vor Zwangs-Flush 2s → verhindert, dass die 300
  Nachrichten aus S7 bei geringer Stückzahl unnötig lange im Puffer
  hängen, bevor sie überhaupt einen Schreibversuch auslösen.

Diese Werte sind Empfehlungen für die Spezifikation und müssen im
Umsetzungsplan nicht zwingend 1:1 übernommen werden, sollten aber die
gleiche Konsistenz-Rechnung gegen S3/S4/S7 nachweisen, falls sie geändert
werden.

**Kein HTTP-Server zwingend nötig**: Da `batch-writer` keine REST-API hat
(Abschnitt 1), ist offen, ob überhaupt ein Actuator-Health-Endpunkt intern
betrieben wird. Falls ja, veröffentlicht er keinen Port (Projektregel).
Diese Detailfrage gehört in `docs/plan-batch-writer.md`.

---

## 11. Abnahmekriterien

| Szenario | Vorgabe | Adressiert durch |
|---|---|---|
| S1 | `mvn clean test` im Wurzelverzeichnis, ein Lauf, alles grün | Root-Build (Abschnitt 0, Entscheidung 15) - betrifft das Gesamtprojekt, nicht den fachlichen Teil dieser Spezifikation |
| S2 | Frischer Klon, `docker compose up -d --build`, alle Dienste laufen, kein Dienst veröffentlicht einen Port | `batch-writer` fügt sich ohne Port in die bestehende `docker-compose.yml` ein (Abschnitt 1, Abgrenzung) |
| S3 | 1000 Nachrichten über `POST /messages`, nach spätestens 60s alle in der Tabelle, Queue leer | Batching (Abschnitt 6, 10) |
| S4 | `batch-writer` gestoppt, 1000 Nachrichten gesendet, dann gestartet: nichts verloren, höchstens 100 Transaktionen | Durable Queue (Abschnitt 3), Batching (Abschnitt 6, 10) |
| S5 | Dieselbe Nachricht zweimal direkt in `chat.persist`, nur mit Header `content_type`: genau eine Zeile, nichts in `chat.dlq` | Nachrichtenvertrag (Abschnitt 2), Duplikatbehandlung (Abschnitt 5) |
| S6 | Zwei Instanzen, 1000 Nachrichten: beide hängen an der Queue, alle da, keine doppelt | Verhalten bei mehreren Instanzen (Abschnitt 9), Duplikatbehandlung (Abschnitt 5) |
| S7 | Postgres 15s offline, 300 Nachrichten gesendet, Postgres danach wieder gestartet: binnen höchstens 90s alle gespeichert, ohne manuellen Neustart | Verhalten bei Datenbankausfall (Abschnitt 8), Retry-Verhalten (Abschnitt 6), Konfiguration (Abschnitt 10) |
| S8 | Quelltext folgt `CLAUDE.md` (keine Streams, Kommentar über jeder Klasse/Methode), `.env` nicht im Repo | Umsetzungsrichtlinie, nicht Teil des fachlichen Verhaltens dieser Spezifikation - gilt für `docs/plan-batch-writer.md` und die Implementierung |

---

## 12. Offene Punkte für docs/plan-batch-writer.md

Diese Spezifikation legt das Verhalten fest, aber bewusst nicht die
technische Umsetzung im Detail. Folgendes gehört in den Umsetzungsplan:

- Genauer Mechanismus für Backoff und Retry-Zähler (z. B. RabbitMQ
  `x-death`-Header auswerten vs. eigene Retry-Queue mit TTL vs. In-Memory-
  Wartezeit vor dem Reject).
- Reihenfolge der Umsetzung: Anpassung chat-service (Nachrichtenvertrag,
  Entfernen `MessageConsumer`, Schemaänderung) vor erstem Start von
  `batch-writer`.
- Konkreter Aufbau der Testumgebung (Testcontainers für RabbitMQ und
  PostgreSQL) für S3-S7.
- Ob und wie ein interner Health-Check (Actuator) betrieben wird.
- Konkrete Docker-Compose-Ergänzung (Service-Definition, Umgebungsvariablen,
  `depends_on`).
