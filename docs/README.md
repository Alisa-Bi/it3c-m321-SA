# M321 — Chat-App (Klasse IT3c)

Lernprojekt zum Modul **M321 Verteilte Systeme / Microservices**. Wir bauen gemeinsam eine
Chat-Anwendung aus mehreren Services, die über eine Message Queue miteinander reden und mit
docker-compose gestartet werden.

## Für Lernende: so startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:
   ```bash
   git clone https://github.com/<dein-benutzername>/it3c-m321.git
   cd it3c-m321
   ```
3. Voraussetzungen installieren: **Java 21**, **Maven**, **Docker Desktop**, **Git**.
4. Lokale Umgebungsdatei anlegen und die Werte anpassen:
   ```bash
   cp .env.example .env
   ```
5. Die Planung lesen (siehe unten) — erst verstehen, dann programmieren.

Alle Aufgaben werden in **deinem Fork** gelöst. Das Original-Repository bleibt die Referenz.

## Bauen, testen, starten

```bash
mvn test                         # alle Tests, RabbitMQ kommt per Testcontainers
docker compose up --build        # RabbitMQ, PostgreSQL, chat-service und batch-writer im Netz chat-net
```

Der `chat-service` veröffentlicht bewusst **keinen Port** auf den Host. Der einzige offene Port
des Gesamtsystems gehört später dem Gateway.

## Was gebaut wird

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21 | `POST /messages` nimmt Nachrichten an und veröffentlicht sie auf `chat.persist` und `chat.delivery`; `GET /stream` liefert sie live per SSE aus und holt verpasste nach. Skalierbar (`--scale chat-service=2`) | vorhanden |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services, Fanout-Exchange `chat.delivery` | vorhanden |
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank | vorhanden |
| postgres | PostgreSQL 16 | Speichert den Chat-Verlauf; der `chat-service` liest daraus nur beim Nachholen | vorhanden |
| keycloak | Keycloak | Login (OIDC) | folgt |
| web-gateway | nginx | Einziger nach aussen offener Port | folgt |
| Web-UI | React | Browser-Client | folgt |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Live-Zustellung: `GET /stream`

`GET /stream?roomId=<uuid>` öffnet eine SSE-Verbindung (Server-Sent Events) für einen Raum. Jede
`chat-service`-Instanz hängt über eine eigene, exklusive Queue an der Fanout-Exchange `chat.delivery`
und verteilt eingehende Nachrichten an die Verbindungen des passenden Raums. So erreicht eine
Nachricht auch Clients, die an einer anderen Instanz hängen. Die Spezifikation steht in
[`docs/spec-live-zustellung.md`](docs/spec-live-zustellung.md).

Jedes Ereignis hat die Form `id: <uuid>` und `data: <json>`. Zeilen, die mit `:` beginnen
(`:connected`, `:heartbeat`), sind Kommentare und halten die Verbindung offen.

**Neuverbindung (Replay).** Sendet der Client den Header `Last-Event-ID` mit der `id` der letzten
erhaltenen Nachricht, liefert der Server zuerst alle späteren Nachrichten des Raums nach und wechselt
dann ohne Lücke und ohne Doppelte in den Live-Betrieb. Die Nachrichten kommen aus PostgreSQL (nur
lesend) und aus einem Ringbuffer im Arbeitsspeicher, der die jüngsten noch nicht gespeicherten
Nachrichten enthält. Fehler stehen vor dem HTTP-Status 200:

| Status | Bedeutung |
|---|---|
| 400 | `roomId` oder `Last-Event-ID` fehlt oder ist keine UUID, oder die Nachricht gehört zu einem anderen Raum |
| 409 | Die Nachricht ist weder in der Datenbank noch im Ringbuffer (zu alt) |
| 503 | PostgreSQL ist nicht erreichbar oder zu langsam |

Ohne `Last-Event-ID` läuft `/stream` auch ohne PostgreSQL und ohne `batch-writer`.

### Konfiguration

Alle Variablen stehen mit Beispielwerten in [`.env.example`](.env.example) und werden von
`docker-compose.yml` an den `chat-service` weitergegeben.

| Variable | Standard | Wirkung |
|---|---|---|
| `CHAT_MAX_CONTENT_BYTES` | 4096 | Grösste erlaubte Nachricht (UTF-8-Bytes), sonst HTTP 400 |
| `CHAT_SSE_TIMEOUT` | 1800000 | Lebensdauer einer SSE-Verbindung in Millisekunden |
| `CHAT_SSE_HEARTBEAT_INTERVAL` | 15s | Abstand der `:heartbeat`-Kommentare |
| `CHAT_DELIVERY_PREFETCH` | 10 | Unbestätigte Nachrichten pro Consumer der Zustell-Queue |
| `CHAT_DELIVERY_QUEUE_MAX_LENGTH` | 990 | Obergrenze der Zustell-Queue je Instanz (älteste Nachricht wird verworfen) |
| `CHAT_RINGBUFFER_PER_ROOM` | 5000 | Ringbuffer: Nachrichten pro Raum |
| `CHAT_RINGBUFFER_TOTAL` | 10000 | Ringbuffer: Nachrichten insgesamt je Instanz |
| `CHAT_SSE_REPLAY_DB_TIMEOUT` | 5s | Höchstdauer der Datenbankabfrage beim Nachholen |
| `CHAT_SSE_REPLAY_BUFFER_MAX` | 1000 | Zwischenspeicher je Verbindung während des Nachholens, bei Überlauf endet nur diese Verbindung |

### Bekannte Grenzen

- Die Zustellung ist schnell, aber nicht verlustfrei und nicht exactly-once. Duplikate erkennt der Client an der `id`.
- Die Zustell-Queue verwirft bei Überlast die älteste Nachricht (`drop-head`). Ein Client, der verbunden bleibt, merkt das nicht. Erst eine Neuverbindung mit `Last-Event-ID` holt die Lücke nach, soweit die Nachricht noch in PostgreSQL oder im Ringbuffer steht.
- Der Ringbuffer liegt nur im Arbeitsspeicher und geht bei einem Neustart verloren. Er ist begrenzt und ersetzt keine Historie.
- Das Nachholen hat keine Obergrenze für die Anzahl Nachrichten, begrenzt ist nur die Dauer der Datenbankabfrage.
- Ist ein Cursor weder in der Datenbank noch im Ringbuffer, kann der Server nichts nachliefern (409).
- Pausiert eine Instanz länger als etwa 60 Sekunden, schliesst RabbitMQ die Verbindung, die exklusive Queue verschwindet und ihre Nachrichten gehen verloren.
- Die Reihenfolge ist nach `(sent_at, id)` eindeutig, aber nicht global: RabbitMQ garantiert die Reihenfolge nur pro Kanal. Bei mehreren gleichzeitigen Absendern kann sie von der Sendereihenfolge abweichen.
- Weitere Einschränkungen: Abschnitt 9 der Spezifikation.

### Abnahme: Szenarien S1 bis S11

Alle Befehle laufen im Wurzelverzeichnis, Docker muss laufen. Für die Skripte muss der Stack mit
`docker compose up -d --build` laufen. In PowerShell einmal pro Fenster
`Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`.

| Szenario | Prüfung |
|---|---|
| S1 Tests | `mvn clean test` |
| S2 Frischer Start | `cp .env.example .env`, `docker compose up -d --build`, `docker compose ps`; `docker compose config` darf kein `published:` enthalten |
| S3 Zwei Instanzen | `.\scripts\pruefe-live-zustellung.ps1 -Scenarios S3` und Test `TwoInstancesDeliveryIntegrationTest` |
| S4 Queue-Lebenszyklus | `.\scripts\pruefe-live-zustellung.ps1 -Scenarios S4` und Test `DeliveryQueueIntegrationTest` |
| S5 Ohne PostgreSQL und batch-writer | `.\scripts\pruefe-live-zustellung.ps1 -Scenarios S5` |
| S6 Queue-Limit | `.\scripts\pruefe-live-zustellung.ps1 -Scenarios S6` und Test `QueueLimitIntegrationTest` |
| S7 Wiederverbindung | `.\scripts\pruefe-live-zustellung.ps1 -Scenarios S7` und Test `ReconnectIntegrationTest` |
| S8 Code und Konfiguration | `git ls-files .env` (leer); Suche nach `.stream(`, `Stream<`, `.map(`, `.filter(`, `.collect(` in `backend/chat-service/src` (keine Treffer); Kommentare von Hand |
| S9 Nachrichtengrösse | `mvn -pl backend/chat-service test -Dtest=MessageSizeLimitIntegrationTest` |
| S10 Zeitstempel | `mvn -pl backend/chat-service test -Dtest=SentAtPrecisionTest` |
| S11 JSON nach dem Nachholen | `mvn -pl backend/chat-service test -Dtest=ReplayJsonFormatIntegrationTest` |

Das Skript ohne `-Scenarios` prüft S3 bis S7 nacheinander.

## Dokumente

- [`PLANUNG.md`](PLANUNG.md) — Auftrag, Stack, Architektur, Nachrichtenfluss, Queues, Datenmodell,
  Umsetzungsreihenfolge. Das ist die Grundlage für alles Weitere.
- [`docs/design/2026-08-28-chat-app-planung.html`](docs/design/2026-08-28-chat-app-planung.html)
  — grafische Fassung der Planung, lokal im Browser öffnen.
- [`docs/plan-chat-service.md`](docs/plan-chat-service.md) — Schritt-für-Schritt-Plan, nach dem
  der `chat-service` gebaut wurde. Jeder Schritt mit Test.
- [`docs/spec-live-zustellung.md`](docs/spec-live-zustellung.md) und [`docs/plan-live-zustellung.md`](docs/plan-live-zustellung.md) — Spezifikation und Umsetzungsplan der Live-Zustellung.
- [`CLAUDE.md`](CLAUDE.md) — Codestil-Regeln für dieses Projekt. Gelten auch für dich.
- [`docs/flipchart-chat-app.png`](docs/flipchart-chat-app.png) — das Flipchart aus der Lektion,
  von dem die Planung ausgeht.

## Codestil, kurz

Der Massstab ist: **kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile, Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode ein bis zwei Sätze: was sie tut und warum es sie gibt.
- Kommentare auf Deutsch, als Erklärung an eine Mitlernende.

Die vollständigen Regeln stehen in [`CLAUDE.md`](CLAUDE.md).
