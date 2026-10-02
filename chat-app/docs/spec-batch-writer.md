# Spezifikation: batch-writer

Massstab für dieses Dokument: Eine Mitschülerin könnte `batch-writer` allein
daraus bauen, ohne nachzufragen. Jede Aussage hier ist entweder direkt aus
`PLANUNG.md` belegt (mit Abschnittsangabe) oder als eigene, begründete
Entscheidung gekennzeichnet.

Diese Fassung ersetzt eine frühere Version, die gegen ein anderes,
inzwischen verworfenes Planungsdokument geschrieben war. Die wichtigsten
Korrekturen gegenüber der alten Fassung: nur **ein** `id`-Feld statt zweier
UUIDs, `JdbcTemplate` statt JPA, keine Authentifizierung auf chat-service,
Batch-Parameter aus PLANUNG.md statt selbst gewählt, `chat.persist` als
direkte Queue statt eigener Exchange.

---

## 1. Zweck und Abgrenzung

**Zweck**: `batch-writer` ist laut PLANUNG.md 3.1 der **einzige
Datenbank-Schreiber** im System. Er konsumiert Nachrichten aus der Queue
`chat.persist` und schreibt sie gebündelt per Bulk-Insert in die Tabelle
`message`. Grund laut PLANUNG.md 1 und 4.1: Das System soll 100'000+
Nachrichten/Minute zeigen können; Einzel-Inserts wären dafür ungeeignet,
Batches mit `ON CONFLICT DO NOTHING` sind die gewählte Lösung (PLANUNG.md
3.6, 4.1).

**Ausdrücklich nicht Teil dieser Abgabe** (laut Aufgabenstellung, nicht
laut PLANUNG.md - PLANUNG.md beschreibt die Zielarchitektur, die
Aufgabenstellung grenzt den aktuellen Zwischenstand ein):

- Chat-Historie lesen (GET-Pfad bleibt unverändert liegen, wird nicht
  geprüft)
- Räume und Mitgliedschaften (`ROOM`, `ROOM_MEMBER` aus PLANUNG.md 3.7)
- Keycloak, `web-gateway`, `load-generator`

Diese Teile werden **nicht entfernt**, nur nicht weiter ausgebaut oder
geprüft. Code, der dazu existiert, bleibt liegen.

**Build- und Stack-Umfang dieser Abgabe**: Da S1 den kompletten
`mvn clean test`-Lauf im Wurzelverzeichnis prüft und S2 den kompletten
`docker compose up -d --build`-Lauf, zählt für beide Szenarien alles, was
im Root-POM bzw. in `docker-compose.yml` aufgeführt ist - unabhängig
davon, ob der jeweilige Dienst selbst bewertet wird. `api-gateway` und
`user-service` bleiben deshalb als Code im Repository liegen, werden aber
aus dem Root-`<modules>`-Eintrag und aus `docker-compose.yml`
herausgenommen: Sie bringen in dieser Abgabe keine Punkte, könnten aber
als Risiko in genau die beiden Szenarien hineinwirken, die den gesamten
Build bzw. Stack prüfen. `keycloak` entfällt aus `docker-compose.yml` aus
demselben Grund und weil `chat-service` ohnehin ohne Authentifizierung
läuft (siehe unten).

**Direkte Konsequenz aus "Keycloak nicht Teil dieser Aufgabe" plus
PLANUNG.md 3.1**: Laut PLANUNG.md wird das Token *ausschliesslich* am
Gateway geprüft - *"Die inneren Dienste vertrauen dem internen Netz"*. Da
das Gateway in dieser Abgabe nicht existiert, ist `chat-service` für
diesen Zwischenstand ohne Authentifizierung erreichbar. Das ist keine
Abweichung von PLANUNG.md, sondern genau die dort beschriebene Architektur
- nur ohne den (hier nicht geforderten) äusseren Wachposten davor. Die
bestehende `SecurityConfig`/`oauth2-resource-server`-Einrichtung in
chat-service wird entfernt bzw. deaktiviert.

**Was `batch-writer` nicht tut**:
- Kein REST-Endpunkt, kein veröffentlichter Port (PLANUNG.md 3.1: "genau
  ein Port-Mapping im ganzen docker-compose.yml", und das ist web-gateway,
  nicht batch-writer).
- Kein Lesezugriff auf `message` - das bleibt, falls überhaupt, bei
  chat-service.
- Kein Zustellpfad (`chat.delivery`) - das ist Aufgabe des (hier nicht
  gebauten) `web-gateway` (PLANUNG.md 3.4).
- Kein JPA/Hibernate - bewusst `JdbcTemplate` (PLANUNG.md 2.1: *"Bewusst
  kein JPA im Batch-Writer: batchUpdate ist genau das, was wir zeigen
  wollen"*).

---

## 2. Vertrag: was auf `chat.persist` ankommt

**Woher das feststeht**: PLANUNG.md 3.4 (Sequenzdiagramm) und 3.7
(Datenmodell).

Laut 3.4 vergibt **chat-service**, nicht batch-writer, sowohl die
Nachrichten-ID als auch den Zeitstempel, bevor publiziert wird: *"UUID
vergeben, Server-Zeitstempel setzen, Empfänger ermitteln"* - danach erst
`publish -> chat.persist`. `batch-writer` erzeugt beim Schreiben also
nichts mehr selbst, er übernimmt die Werte unverändert aus der Nachricht.

Felder, abgeleitet aus dem ER-Diagramm in 3.7 (`MESSAGE`-Entität), als
JSON (Jackson-Standard: camelCase):

| JSON-Feld | Typ | Herkunft laut PLANUNG.md | Pflicht |
|---|---|---|---|
| `id` | UUID (String) | "vom chat-service vergeben" (3.7), zugleich Primärschlüssel und Idempotenz-Schlüssel (3.6) | ja |
| `roomId` | UUID (String) | Fremdschlüssel auf `ROOM` (3.7) | ja |
| `senderId` | String | "sub aus Keycloak" (3.7) - hier als String, da Keycloak in diesem Zwischenstand nicht läuft und kein echtes JWT existiert, aus dem ein `sub` stammen könnte | ja |
| `senderName` | String | "denormalisiert" (3.7) - bewusst mitgespeichert, damit die Historie lesbar bleibt, auch wenn ein Konto später gelöscht wird | ja |
| `content` | String | Nachrichtentext (3.7) | ja, nicht leer |
| `sentAt` | ISO-8601-Zeitstempel | "Server-Zeitstempel setzen" (3.4) - von chat-service gesetzt, nicht von batch-writer | ja |

**Transport**: JSON über AMQP, `content-type: application/json` (so im
Testszenario S5 vorausgesetzt).

**Kein separates `messageId`-Feld**: anders als in der verworfenen
Vorfassung dieser Spezifikation gibt es nur **ein** `id`-Feld. PLANUNG.md
3.6 ist hier eindeutig: *"die Spalte message.id ist Primärschlüssel,
ON CONFLICT DO NOTHING verwirft das Duplikat beim Einfügen"* - es gibt in
der Zielarchitektur keine zweite, separate Idempotenz-Spalte.

**Verhalten bei Vertragsverletzung** (kein valides JSON, fehlendes
Pflichtfeld, leerer `content`): zählt als fehlgeschlagener
Verarbeitungsversuch, siehe Abschnitt 5.3 - nach PLANUNG.md 3.5 gibt es
keine Sonderbehandlung für "kaputte" gegenüber "technisch nicht
verarbeitbaren" Nachrichten, beide zählen gleich.

---

## 3. RabbitMQ-Topologie

**Woher das feststeht**: PLANUNG.md 3.5 (Tabelle).

| Name | Typ laut PLANUNG.md 3.5 | Erzeuger | Verbraucher |
|---|---|---|---|
| `chat.persist` | **Queue** (kein Exchange genannt) | chat-service | batch-writer (M Instanzen) |
| `chat.dlq` | Queue | RabbitMQ (automatisch) | - |

**Begründung, warum keine eigene `TopicExchange` für `chat.persist`**: Im
Unterschied zu `chat.delivery`, das in derselben Tabelle ausdrücklich als
*"Exchange (fanout)"* geführt wird, steht bei `chat.persist` nur "Queue".
Das ist eine bewusste Unterscheidung im Dokument, kein Zufall:
`chat.delivery` braucht einen Fanout-Exchange, weil mehrere
`web-gateway`-Instanzen gleichzeitig mitlesen sollen (3.5: *"jede Instanz
bindet eine eigene, exklusive Queue"*). `chat.persist` braucht das nicht -
alle `batch-writer`-Instanzen teilen sich dieselbe Queue
(Competing Consumers, 3.5 letzter Absatz). chat-service veröffentlicht
deshalb direkt auf die Queue `chat.persist` (Standard-Exchange, Routing-Key
= Queue-Name), ohne zusätzlichen benannten Exchange dazwischen.

**Dead-Lettering, 3 Versuche (PLANUNG.md 3.5: *"nach 3 fehlgeschlagenen
Versuchen"*)**:

Mit einfachem `NACK(requeue=true)` ohne Verzögerung wären 3 Versuche
innerhalb von Millisekunden verbraucht - ein 15-Sekunden-Ausfall (S7)
hätte dann *garantiert* Nachrichten in der DLQ zur Folge, obwohl S7 das
Gegenteil verlangt. Deshalb: zwischen den Versuchen liegt eine **feste
Wartezeit von 15 Sekunden**, technisch über eine Retry-Queue mit
Nachrichten-TTL (die Nachricht landet nach Ablauf der TTL automatisch
wieder in `chat.persist`, kein aktives Warten im Consumer-Code).

Rechnung gegen S7 (Postgres 15s offline): Versuch 1 bei t=0 (schlägt fehl,
Postgres ist down), Versuch 2 bei t=15 (knapp, Postgres kommt gerade
hoch), Versuch 3 bei t=30 (Postgres ist sicher wieder da, gelingt). Erfolg
spätestens bei t≈30s - deutlich innerhalb der 90-Sekunden-Grenze aus S7.

Nach dem dritten erfolglosen Versuch: Nachricht geht nach `chat.dlq`
(DLQ-Routing über das `x-dead-letter-exchange`-Argument der Retry-Queue).

**Kein separates `chat.delivery` in dieser Abgabe**: chat-service
veröffentlicht laut PLANUNG.md 3.4 sowohl nach `chat.persist` als auch
nach `chat.delivery`. Da `web-gateway` nicht Teil dieser Abgabe ist, bindet
niemand eine Queue an `chat.delivery` - Nachrichten dorthin verschwinden
folgenlos (ein Fanout-Exchange ohne gebundene Queue verwirft still). Das
ist beabsichtigt und wird hier nur festgehalten, damit es nicht wie ein
Bug aussieht.

---

## 4. Datenmodell und Konfiguration

**Woher das feststeht**: PLANUNG.md 3.7 (ER-Diagramm), wortwörtlich
übernommen, kein eigener Entwurf.

### 4.1 Tabelle `message`

| Spalte | Typ | Constraint | Begründung |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY | "vom chat-service vergeben" (3.7); zugleich Idempotenz-Schlüssel (3.6) |
| `room_id` | UUID | NOT NULL | Fremdschlüssel auf `room.id`, kein `REFERENCES`-Constraint, da `ROOM` nicht Teil dieser Abgabe ist (würde die Tabelle an eine nicht existierende Tabelle binden) |
| `sender_id` | VARCHAR | NOT NULL | "sub aus Keycloak" (3.7) |
| `sender_name` | VARCHAR | NOT NULL | "denormalisiert" (3.7) |
| `content` | TEXT | NOT NULL | Nachrichtentext |
| `sent_at` | TIMESTAMPTZ | NOT NULL | von chat-service gesetzt (3.4), nicht von batch-writer |

**Index**: `(room_id, sent_at DESC)` - PLANUNG.md 3.7 wörtlich: *"das ist
die einzige Abfrage im Lesepfad"*. Wird hier bereits angelegt, auch wenn
der Lesepfad selbst nicht Teil dieser Abgabe ist, weil die Tabelle sonst
später erneut angefasst werden müsste.

**Wo das Schema entsteht**: Da `batch-writer` bewusst `JdbcTemplate` statt
JPA verwendet (PLANUNG.md 2.1), gibt es kein `ddl-auto`, das das Schema
automatisch erzeugen könnte. Das Schema entsteht über ein SQL-Skript
(`infrastructure/postgres/init.sql` bzw. ein batch-writer-eigenes
Migrationsskript), das beim ersten Start von PostgreSQL ausgeführt wird -
**nicht** durch chat-service oder batch-writer zur Laufzeit. Genauer
Mechanismus (einmaliges Init-Skript vs. Flyway) ist eine Umsetzungsfrage,
siehe `docs/plan-batch-writer.md`.

### 4.2 Konfiguration

| Variable | Bedeutung | Quelle |
|---|---|---|
| `POSTGRES_USER` | Datenbank-Benutzer | PLANUNG.md/Aufgabenstellung: direkt, keine Umbenennung |
| `POSTGRES_PASSWORD` | Datenbank-Passwort | s. o. |
| `POSTGRES_DB` | Datenbankname | s. o. |
| `RABBITMQ_DEFAULT_USER` | RabbitMQ-Benutzer | analog zu bisherigem Muster |
| `RABBITMQ_DEFAULT_PASS` | RabbitMQ-Passwort | analog zu bisherigem Muster |
| Batch-Grösse | 500 Nachrichten | PLANUNG.md 3.6, 4.1 wörtlich: "Stapelgrösse 500 Nachrichten oder 200 ms" |
| Max. Wartezeit vor Flush | 200 ms | s. o. |
| Retry-Verzögerung | 15 s | eigene Entscheidung, siehe Abschnitt 3, Begründung gegen S7 gerechnet |
| Retry-Obergrenze | 3 Versuche | PLANUNG.md 3.5 wörtlich |
| Queue-Name | `chat.persist` | PLANUNG.md 3.5 |
| DLQ-Name | `chat.dlq` | PLANUNG.md 3.5 |

Alle Variablen kommen direkt und ohne Umbenennung aus `.env.example` -
keine zusätzliche Zuordnungsebene wie in der Vorfassung.

---

## 5. Verhalten

### 5.1 Normalfall

Nachrichten aus `chat.persist` werden gepuffert, bis **500 Stück**
erreicht sind **oder 200 ms** vergangen sind (PLANUNG.md 3.6,
Flussdiagramm), je nachdem, was zuerst eintritt. Dann: ein Bulk-Insert
(`JdbcTemplate.batchUpdate`, `ON CONFLICT (id) DO NOTHING`), danach ein
gemeinsames ACK für den ganzen Stapel. Das ist At-least-once (PLANUNG.md
3.6): erst nach erfolgreichem COMMIT wird bestätigt.

### 5.2 Duplikate (S5)

Zwei Nachrichten mit identischem `id` führen zu genau einer Zeile -
`ON CONFLICT (id) DO NOTHING` verwirft die zweite beim Einfügen, ohne
Fehler, ohne DLQ-Eintrag. Das ist keine Sonderbehandlung, sondern dieselbe
Bulk-Insert-Anweisung wie im Normalfall - Duplikate sind kein eigener
Codepfad, sondern eine Eigenschaft der SQL-Anweisung selbst.

**Wichtig für die Messung**: Da die Szenarien laut Aufgabenstellung ohne
Aufräumen nacheinander auf demselben Stack laufen, bedeutet "genau eine
Zeile" bei S5 nicht "eine Zeile in der ganzen Tabelle" (die enthält zu dem
Zeitpunkt längst Tausende Zeilen aus S3/S4), sondern: genau eine Zeile mit
dieser spezifischen `id`.

### 5.3 Fehlerhafte oder nicht verarbeitbare Nachrichten

Jeder Fehler beim Verarbeiten eines Batches (ungültiges JSON, fehlendes
Pflichtfeld, Datenbank nicht erreichbar) führt zum selben Ablauf:
Retry über die Retry-Queue (15 s Verzögerung), bis zu 3 Versuche
insgesamt, danach `chat.dlq` (siehe Abschnitt 3). PLANUNG.md unterscheidet
nicht zwischen Fehlerursachen, deshalb hier auch nicht - eine Unterscheidung
wäre eine Abstraktion, die das Dokument nicht vorsieht.

**Einschränkung, offen benannt**: Da ein Batch mehrere Nachrichten
zusammenfasst, löst eine einzelne kaputte Nachricht im Batch denselben
Retry für den **ganzen Batch** aus (PLANUNG.md 3.6 beschreibt Bestätigung
und Fehlschlag nur auf Batch-Ebene, nicht pro Nachricht). Das kann dazu
führen, dass 499 valide Nachrichten wegen einer einzigen kaputten erneut
verarbeitet werden. Das ist eine direkte Konsequenz aus "ein Bulk-Insert
pro Stapel" und wird hier nicht stillschweigend anders gelöst, als
PLANUNG.md es vorsieht.

### 5.4 Datenbankausfall (S7)

Siehe Abschnitt 3 für die Herleitung der 15-Sekunden-Retry-Verzögerung.
Zusätzlich: der `batch-writer`-Prozess selbst darf durch einen
Datenbankfehler nicht abstürzen - ein nicht erreichbarer Postgres-Server
ist ein zu behandelnder Zwischenzustand, kein fataler Fehler. Kein
manueller Neustart (S7-Vorgabe wörtlich).

### 5.5 Mehrere Instanzen (S6)

RabbitMQ verteilt Nachrichten einer Queue automatisch auf mehrere
verbundene Consumer (Competing Consumers, PLANUNG.md 3.5 letzter Absatz) -
keine Zusatzlogik in `batch-writer` nötig. Die Eindeutigkeit aus 5.2 gilt
unverändert auch hier, durchgesetzt durch denselben `ON CONFLICT (id) DO
NOTHING` auf Datenbankebene, nicht durch Anwendungslogik - das ist
notwendig, weil zwei Instanzen nie durch eine reine Anwendungsprüfung
("gibt es das schon?") race-sicher gemacht werden können.

---

## 6. Abnahmekriterien

Für jedes Szenario: Vorgabe, Messbefehl, Begründung, warum dieser Befehl
das Richtige misst.

**Hinweis zur gesamten Tabelle**: Da die Szenarien laut Aufgabenstellung
ohne Aufräumen nacheinander auf demselben Stack laufen, sind "vorher"/
"nachher"-Werte als **Differenzen** zu verstehen, nicht als Absolutwerte,
ausser bei S5 (dort zählt die `id` der konkreten Testnachricht, nicht die
Gesamtzahl).

| # | Vorgabe | Messbefehl | Warum das misst, was gefordert ist |
|---|---|---|---|
| S1 | `mvn clean test`, ein Lauf, alles grün | `mvn clean test` im Repository-Root, Rückgabecode `0` | Rückgabecode ist die eindeutige, von Maven selbst garantierte Erfolgsmeldung |
| S2 | Frischer Klon, `.env` aus `.env.example`, `docker compose up -d --build`, alle Dienste laufen, kein Port veröffentlicht | `docker compose ps` → alle Zeilen `State: running`; `docker compose config` → kein `ports:`-Eintrag | `docker compose ps` zeigt den tatsächlichen Laufzustand, `config` zeigt die deklarierten Portmappings unabhängig vom Laufzustand |
| S3 | 1000 Nachrichten über `POST /messages`, binnen 60s alle in der Tabelle, Queue leer | Vorher: `docker compose exec postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -t -c "SELECT COUNT(*) FROM message;"` notieren. 1000x `POST /messages`. Danach alle paar Sekunden erneut zählen, bis Differenz = 1000 oder 60s um; zusätzlich `docker compose exec rabbitmq rabbitmqctl list_queues name messages` für `chat.persist` → 0 | Differenzmessung statt Absolutwert, weil der Stack zwischen Szenarien nicht geleert wird (s. Hinweis oben) |
| S4 | batch-writer gestoppt, 1000 gesendet, dann gestartet: nichts verloren, höchstens 100 Transaktionen | `docker compose stop batch-writer`; 1000x senden; Transaktionszähler vorher notieren: `docker compose exec postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -t -c "SELECT xact_commit FROM pg_stat_database WHERE datname='$POSTGRES_DB';"`; `docker compose start batch-writer`; nach Abschluss erneut zählen, Differenz der Zeilen = 1000, Differenz von `xact_commit` ≤ 100 | `pg_stat_database.xact_commit` ist Postgres' eigener, von aussen abfragbarer Transaktionszähler - kein Zählen im Anwendungscode nötig, das man manipulieren könnte |
| S5 | Dieselbe Nachricht zweimal direkt in `chat.persist`, nur mit `content_type`-Header: genau eine Zeile, nichts in `chat.dlq` | Eine `id` wählen, zweimal mit identischem JSON-Body und nur `content_type: application/json` direkt in `chat.persist` veröffentlichen (z. B. `rabbitmqadmin publish`); danach `SELECT COUNT(*) FROM message WHERE id = '<id>';` → 1; `rabbitmqctl list_queues name messages` für `chat.dlq` → unverändert zum Stand vor S5 | Zählt gezielt die eine `id`, nicht die Gesamttabelle - robust gegenüber den Zeilen aus S3/S4 |
| S6 | Zwei Instanzen, 1000 Nachrichten: beide an der Queue, alle da, keine doppelt | `docker compose up -d --build --scale batch-writer=2`; `rabbitmqctl list_consumers` → 2 Einträge für `chat.persist`; 1000 senden; danach `SELECT COUNT(*) FROM message WHERE id = ANY(<Liste der 1000 gesendeten ids>);` → genau 1000 | Da `id` Primärschlüssel ist, kann "doppelt" in der Tabelle gar nicht erst vorkommen - der eigentliche Test ist, ob alle 1000 *ankommen*, nicht ob keine doppelt sind |
| S7 | Postgres 15s offline, 300 gesendet, danach wieder gestartet: binnen 90s alle 300 in der Tabelle, kein manueller Neustart | `docker compose stop postgres`; 300 senden; 15s warten; `docker compose start postgres`; alle paar Sekunden zählen bis Differenz = 300 oder 90s um; parallel `docker compose ps batch-writer` beobachten (`State` bleibt durchgehend `running`, `RestartCount` über `docker inspect` bleibt `0`) | Die Restart-Count-Prüfung stellt sicher, dass "läuft ohne Neustart" nicht durch eine Docker-Restart-Policy erschlichen wird, die den Container neu startet, statt ihn durchlaufen zu lassen |
| S8 | Quelltext folgt `CLAUDE.md`, `.env` nicht im Repo | Manuelle Durchsicht von `batch-writer/src/` gegen `CLAUDE.md`-Regeln (keine Streams, Kommentar über jeder Klasse/Methode); `git log --all --full-history -- .env` → keine Treffer | `.gitignore` allein beweist nichts rückwirkend - der `git log`-Befehl prüft die tatsächliche Historie, nicht nur den aktuellen Zustand |

---

## 7. Entscheidungen zum Build- und Stack-Umfang

Die folgenden zwei Punkte waren in einer früheren Fassung offen und sind
jetzt entschieden:

**`docker-compose.yml`-Bereinigung**: `keycloak`, `api-gateway`,
`user-service` werden aus `docker-compose.yml` entfernt (Code bleibt im
Repository liegen, nur nicht Teil dieses Stacks). Begründung: Alle acht
Szenarien müssen bei der Abgabe reproduzierbar erfüllt sein; S2 prüft den
kompletten `docker compose up -d --build`-Lauf, nicht nur die bewerteten
Dienste. Ein fehlerhaft startender, nicht bewerteter Dienst wäre ein
vermeidbares Risiko für ein Szenario, das tatsächlich zählt.

**Eltern-POM-Umbau gilt für `chat-service` und `batch-writer`**, nicht für
`api-gateway`/`user-service`. Begründung: aus demselben Grund wie oben -
S1 prüft den kompletten Reactor-Build im Wurzelverzeichnis.
`api-gateway`/`user-service` werden deshalb zusätzlich aus dem
Root-`<modules>`-Eintrag entfernt (nicht nur aus `docker-compose.yml`),
damit sie S1 ebenfalls nicht gefährden können, ohne etwas beizutragen.
Sie bleiben als eigenständige, weiterhin per `spring-boot-starter-parent`
buildbare Projekte bestehen, nur ausserhalb des für diese Abgabe
geprüften Builds.

## 8. Verbleibender offener Punkt

- **Migrationsmechanismus fürs Schema** (Abschnitt 4.1): einmaliges
  Postgres-Init-Skript oder ein Werkzeug wie Flyway - beides erfüllt "wo
  entsteht das Schema" unterschiedlich gut testbar. Entscheidung gehört in
  den Umsetzungsplan, nicht hierher, da sie eine Bau-Reihenfolge-Frage ist.
