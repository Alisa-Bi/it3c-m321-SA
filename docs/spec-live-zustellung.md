# Spezifikation: Live-Zustellung von Chat-Nachrichten

Datei: docs/spec-live-zustellung.md

## 1\. Ziel und Umfang

### 1.1 Ziel

Die Chat-Anwendung soll neue Nachrichten zusätzlich zur bestehenden Speicherung live an alle laufenden Chat-Service-Instanzen verteilen.

Dafür wird RabbitMQ verwendet. Jede Chat-Service-Instanz erhält eine eigene temporäre Queue. Ein Fanout-Exchange verteilt neue Nachrichten an alle diese Queues.

Die Chat-Service-Instanzen senden die empfangenen Nachrichten über Server-Sent Events (SSE) an ihre verbundenen Clients.

Die bestehende Speicherung über den Batch-Writer und die Queue chat.persist bleibt bestehen.

### 1.2 Anforderungen

Das System muss folgende Anforderungen erfüllen:

* Neue Nachrichten werden möglichst ohne Verzögerung an verbundene Clients zugestellt.
* Jede laufende Chat-Service-Instanz erhält die veröffentlichten Nachrichten.
* Ein Client erhält nur Nachrichten aus dem ausgewählten Chat-Raum.
* Die Live-Zustellung funktioniert unabhängig von der Datenbankspeicherung.
* Nach einer Unterbrechung kann ein Client verpasste Nachrichten begrenzt nachholen.
* Jede Instanz verwendet begrenzte Ressourcen für die Live-Zustellung.
* Nicht mehr benötigte Instanz-Queues werden automatisch entfernt.

### 1.3 Nicht Bestandteil

Nicht Bestandteil dieser Umsetzung sind:

* Eine vollständige Chat-Historie über SSE.
* Eine garantierte Exactly-once-Zustellung.
* Eine garantierte Zustellung bei beliebig langen Unterbrüchen.
* Der Ersatz des bestehenden Batch-Writers.
* Eine dauerhafte Speicherung der Live-Queues.

### 1.4 Historie und Nachholen

Die vollständige Nachrichtenhistorie bleibt Aufgabe der bestehenden Datenbankspeicherung.

Die Live-Zustellung ist für neue Nachrichten zuständig.

Das Nachholen dient dazu, Nachrichten wiederherzustellen, die ein Client während einer kurzen Unterbrechung verpasst hat.

Für das Nachholen werden die Datenbank und ein begrenzter In-Memory-Ringbuffer verwendet.

Der Ringbuffer ist nicht dauerhaft. Nachrichten, die weder in der Datenbank noch im Ringbuffer verfügbar sind, können nicht wiederhergestellt werden.

## 2\. Nachrichtenformat und Endpoint

### 2.1 Nachrichtenformat

Für neue Nachrichten wird weiterhin das bestehende DTO NewMessageRequest verwendet.

Dateipfad:
`backend/chat-service/src/main/java/ch/m321/chatapp/chat/dto/NewMessageRequest.java`

Das DTO enthält folgende Felder:

|Feld|Java-Typ|Datenbankspalte|Datenbanktyp|
|-|-|-|-|
|id|UUID|id|UUID|
|roomId|UUID|room\_id|UUID|
|senderId|String|sender\_id|VARCHAR(255)|
|senderName|String|sender\_name|VARCHAR(255)|
|content|String|content|TEXT|
|sentAt|Instant|sent\_at|TIMESTAMPTZ|

Die Datenbankdefinition befindet sich unter:
`infrastructure/postgres/init.sql`

Die Tabelle heisst message und nicht messages.

Das tatsächliche JSON-Format wurde an einer echten Nachricht aus chat.persist geprüft.

Das Feld sentAt wird als Zahl serialisiert. Es enthält Sekunden seit dem 1. Januar 1970 mit Nachkommastellen.

Beispiel vor der Kürzung auf Mikrosekunden:
`1794168671.468012602`

Der Server kürzt sentAt bereits beim Erzeugen der Nachricht auf Mikrosekunden. Ein gekürzter Wert hätte beispielsweise sechs Nachkommastellen.

Die Felder id und sentAt werden serverseitig erzeugt. Werte, die ein Client für diese Felder mitsendet, werden überschrieben.

Beim Nachholen aus PostgreSQL wird aus der Datenbankzeile ein NewMessageRequest erstellt. Dieses Objekt wird mit demselben Jackson-Converter serialisiert, der auch beim Publish der Nachricht verwendet wird.

Dadurch wird für Live-Zustellung und Nachholen dasselbe Nachrichtenformat verwendet.

### 2.2 SSE-Endpunkt

Der Chat-Service stellt folgenden Endpunkt bereit:

`GET /stream?roomId=<UUID>`

Der Endpunkt liefert Server-Sent Events mit dem Content-Type text/event-stream.

Der Client kann optional den HTTP-Header Last-Event-ID mitsenden.

Dieser Header enthält die UUID der letzten vollständig empfangenen Nachricht.

Ohne Last-Event-ID wird nur die Live-Zustellung gestartet.

Mit Last-Event-ID versucht der Server, die danach verpassten Nachrichten nachzuliefern.

Eine fehlende oder ungültige roomId führt zu HTTP 400.

Eine ungültige Last-Event-ID führt ebenfalls zu HTTP 400.

Wenn die Cursor-Nachricht nicht zum angegebenen roomId gehört, wird HTTP 400 zurückgegeben, sofern sie in der Datenbank gefunden wird.

Wenn die Cursor-Nachricht weder in der Datenbank noch im Ringbuffer des angegebenen Raums gefunden wird, wird HTTP 409 zurückgegeben.

Wenn PostgreSQL für das Nachholen nicht verfügbar ist, wird HTTP 503 zurückgegeben.

Wenn RabbitMQ nicht verfügbar ist und dadurch keine Live-Zustellung aufgebaut werden kann, wird HTTP 503 zurückgegeben.

Ein erfolgreicher Verbindungsaufbau verwendet HTTP 200.

Die Prüfung des Cursors muss abgeschlossen sein, bevor HTTP 200 und damit die SSE-Verbindung geöffnet wird.

### 2.3 SSE-Nachrichtenformat

Jede Nachricht wird als SSE-Event mit einer ID und einem Datenfeld übertragen.

Die ID entspricht der Nachrichten-ID.

Das Datenfeld enthält die vollständige Nachricht im JSON-Format.

Das JSON-Format entspricht dem überprüften Format von chat.persist.

Ein SSE-Event endet mit einer Leerzeile.

Zusätzlich sendet der Server regelmässig Heartbeats. Ein Heartbeat besteht aus einem SSE-Kommentar und enthält keine Chat-Nachricht.

### 2.4 Heartbeat und Wiederverbindung

Der Server sendet alle 15 Sekunden einen Heartbeat.

Der Client betrachtet die Verbindung als unterbrochen, wenn während 45 Sekunden kein Heartbeat oder SSE-Event eingetroffen ist.

In diesem Fall beendet der Client die Verbindung und baut sie erneut auf.

Bei der neuen Verbindung sendet der Client die ID der letzten vollständig empfangenen Nachricht im Header Last-Event-ID.

Dadurch kann der Server versuchen, die verpassten Nachrichten nachzuliefern.

Ein Heartbeat bestätigt nur, dass die Verbindung noch funktioniert. Er garantiert nicht, dass alle Nachrichten zugestellt wurden.

## 3\. RabbitMQ-Architektur

### 3.1 Exchange

Für die Live-Zustellung wird ein zusätzlicher Exchange verwendet.

Name: `chat.delivery`

Typ: fanout

Der Exchange ist dauerhaft.

Ein Fanout-Exchange verteilt jede veröffentlichte Nachricht an alle daran gebundenen Queues.

Es wird kein Routing Key benötigt.

### 3.2 Queue pro Chat-Service-Instanz

Jede laufende Chat-Service-Instanz erstellt beim Start eine eigene Queue.

Die Queue erhält einen automatisch erzeugten und eindeutigen Namen.

Die Implementierung verwendet eine AnonymousQueue; ihren Namen erzeugt der Spring-Client.

Die Queue wird mit folgenden Eigenschaften erstellt:

* exclusive: true
* auto-delete: true
* durable: false

Die Queue wird mit dem Exchange chat.delivery verbunden.

Die Einstellung exclusive sorgt dafür, dass die Queue an die Verbindung gebunden ist und beim Schliessen dieser Verbindung entfernt wird.

Die Einstellung auto-delete sorgt dafür, dass die Queue entfernt wird, wenn der letzte Consumer wegfällt.

Die Queue ist nicht dauerhaft, da sie nur für die aktuell laufende Instanz benötigt wird.

Die eindeutige Queue pro Instanz ist notwendig, damit jede Instanz eine eigene Kopie jeder Nachricht erhält. Würden mehrere Instanzen dieselbe Queue konsumieren, würden sie die Nachrichten untereinander aufteilen, statt jede Nachricht zu erhalten.

### 3.3 Queue-Limit und Nachrichtenverhalten

Jede Instanz-Queue erhält ein Limit von 990 bereitliegenden Nachrichten.

Die Queue verwendet folgende Einstellungen:

* x-max-length: 990
* x-overflow: drop-head

Bei drop-head wird im Überlastfall die älteste bereitliegende Nachricht entfernt. Die neueste Nachricht wird in die Queue aufgenommen.

Für den Consumer wird ein Prefetch von 10 festgelegt.

Die Rechnung für die maximale Anzahl Nachrichten lautet:

990 bereitliegende Nachrichten plus maximal 10 unbestätigte Nachrichten ergeben maximal 1000 Nachrichten pro Queue.

Diese Grenze gilt nur unter der Voraussetzung, dass genau ein Consumer pro Queue aktiv ist und dieser Consumer höchstens 10 Nachrichten gleichzeitig unbestätigt verarbeitet.

Die Queue-Grenze x-max-length zählt die bereitliegenden Nachrichten. Die unbestätigten Nachrichten werden über den Prefetch begrenzt.

drop-head wird gegenüber reject-publish bevorzugt, weil bei reject-publish die neuesten Nachrichten verworfen würden, sobald die Queue voll ist.

Gerade die neuesten Nachrichten sind für einen Live-Chat besonders wichtig.

Bei reject-publish würde der Publisher nur mit korrekt aktivierten Publisher Confirms zuverlässig über die Ablehnung informiert. Publisher Confirms sind in der bestehenden Konfiguration nicht gesetzt.

Mit drop-head bleiben die neuesten Nachrichten in der Queue. Dafür können ältere Nachrichten verloren gehen.

Dieser Verlust wird dem verbundenen Client nicht automatisch gemeldet.

### 3.4 Bestehende Persistenz-Queue

Die bestehende Queue chat.persist bleibt unverändert. Sie ist eine Queue am Standard-Exchange und kein eigener Exchange.

Die Queue ist dauerhaft (durable). Nachrichten werden standardmässig persistent (Spring-AMQP-Standard) veröffentlicht.

Der bestehende Batch-Writer verarbeitet weiterhin Nachrichten aus dieser Queue und speichert sie in PostgreSQL.

Die neue Live-Zustellung ersetzt weder chat.persist noch den Batch-Writer.

Beim Eingang einer neuen Nachricht veröffentlicht der Chat-Service dieselbe Nachricht an beide Wege:

* chat.persist für die spätere Speicherung.
* chat.delivery für die Live-Zustellung.

Die Nachrichten-ID bleibt dabei identisch.

## 4\. Verhalten und Szenarien

### 4.1 Senden einer Nachricht

Wenn ein Client eine Nachricht sendet, verarbeitet der Chat-Service den bestehenden Request.

Der Server erzeugt die Nachrichten-ID id und den Zeitstempel sentAt selbst.

sentAt wird mit Mikrosekunden-Genauigkeit erzeugt. In MessageService wird dafür Instant.now().truncatedTo(ChronoUnit.MICROS) verwendet.

Vom Client mitgesendete Werte für id und sentAt werden überschrieben.

Vor dem Publish wird geprüft, ob der Inhalt die konfigurierte Grenze CHAT\_MAX\_CONTENT\_BYTES überschreitet. Die Grösse wird anhand der UTF-8-Bytes berechnet.

Wenn der Inhalt zu gross ist, antwortet der POST-Endpunkt mit HTTP 400. Der Inhalt wird niemals abgeschnitten.

Danach veröffentlicht der Chat-Service dieselbe Nachricht zuerst an chat.persist und anschliessend an chat.delivery.

Erst nachdem beide Publish-Aufrufe erfolgreich ausgeführt wurden, antwortet der POST-Endpunkt mit HTTP 202.

Bisher wird keine AMQP-Ausnahme speziell behandelt. Neu fängt der Service eine AMQP-Ausnahme ab und antwortet mit HTTP 503.

Die beiden Publish-Aufrufe sind nicht atomar.

Wenn nur einer der beiden Publish-Aufrufe erfolgreich ist, kann die Nachricht bereits über einen Weg verarbeitet worden sein, obwohl der Request insgesamt fehlschlägt.

In diesem Fall antwortet der POST-Endpunkt mit HTTP 503.

Eine Rücknahme des bereits erfolgreichen Publish-Aufrufs ist nicht möglich.

Die Reihenfolge chat.persist vor chat.delivery stellt sicher, dass eine Nachricht nicht live veröffentlicht wird, bevor der Persistenz-Publish erfolgreich ausgeführt wurde.

### 4.2 Zustellung an mehrere Instanzen

Jede Chat-Service-Instanz konsumiert ihre eigene Queue.

Wenn eine Nachricht an chat.delivery veröffentlicht wird, erhalten alle aktuell verbundenen Instanzen eine Kopie.

Jede Instanz prüft die roomId der Nachricht.

Die Nachricht wird nur an SSE-Verbindungen weitergegeben, die zu diesem Chat-Raum gehören.

Eine Nachricht aus einem anderen Raum wird nicht an diese Verbindung gesendet.

Die Zustellung innerhalb einer Queue erfolgt in der Reihenfolge, in der die Nachrichten in die Queue eingereiht wurden.

Wenn ein Absender mehrere Nachrichten nacheinander sendet und jeweils auf die Antwort des POST-Requests wartet, werden die Nachrichten in dieser Reihenfolge veröffentlicht und eingereiht.

Bei parallelen Requests desselben Absenders kann der Server keine bestimmte Reihenfolge garantieren.

### 4.3 Beenden einer Instanz

Beim normalen Beenden einer Chat-Service-Instanz wird die RabbitMQ-Verbindung geschlossen.

Da die Queue exclusive ist, wird sie beim Schliessen der Verbindung entfernt.

Beim Absturz der Instanz wird die Verbindung ebenfalls geschlossen, sobald RabbitMQ den Verbindungsverlust erkennt.

Danach wird die exklusive Queue entfernt.

Jede laufende Instanz erhält über ihre eigene Queue jede Nachricht und legt sie im eigenen Ringbuffer ab.

### 4.4 Ausfall von Batch-Writer oder PostgreSQL

Die Live-Zustellung ist unabhängig vom Batch-Writer und von PostgreSQL.

Der Chat-Service startet und nimmt Nachrichten an, auch wenn PostgreSQL nicht erreichbar ist. Dafür darf chat-service in docker-compose.yml kein depends\_on: postgres erhalten.

Wenn der Batch-Writer ausfällt, kann die Live-Zustellung weiterhin funktionieren.

Wenn PostgreSQL ausfällt, kann die Live-Zustellung ebenfalls weiterhin funktionieren, sofern RabbitMQ und der Chat-Service verfügbar sind.

Das Nachholen über die Datenbank ist während eines PostgreSQL-Ausfalls nicht möglich.

Nachrichten, die noch nicht gespeichert wurden und auch nicht mehr im Ringbuffer vorhanden sind, können später nicht nachgeholt werden.

Die Queue chat.persist ist dauerhaft und Nachrichten werden persistent veröffentlicht. Nachrichten bleiben auch dann in der Queue, wenn der Batch-Writer gestoppt ist. Nach dem Neustart kann der Batch-Writer sie verarbeiten.

### 4.5 Überlastung und pausierte Instanz

Wenn eine Instanz keine Nachrichten mehr konsumiert, füllt sich ihre Queue.

Sobald das Limit erreicht ist, werden bei drop-head die ältesten bereitliegenden Nachrichten entfernt.

Andere Chat-Service-Instanzen besitzen eigene Queues und sind davon nicht direkt betroffen.

Bei einer längeren Pause kann zusätzlich die RabbitMQ-Verbindung durch den Heartbeat-Mechanismus geschlossen werden.

In diesem Fall wird die exklusive Queue entfernt.

Wenn eine Instanz beispielsweise mit docker pause angehalten wird, bleibt die TCP-Verbindung zunächst bestehen. Der Client erkennt den Ausfall deshalb nicht sofort.

Der Client erkennt die Störung über den Heartbeat-Timeout.

Wenn während 45 Sekunden kein Heartbeat oder SSE-Event eintrifft, beendet der Client die Verbindung und versucht eine neue Verbindung aufzubauen.

Beim Neuaufbau sendet er Last-Event-ID mit der ID der letzten vollständig empfangenen Nachricht.

Der Server versucht anschliessend, die verpassten Nachrichten nachzuliefern.

Das Nachholen funktioniert, wenn der Cursor und die benötigten Nachrichten in der Datenbank oder im Ringbuffer des angegebenen Raums vorhanden sind.

### 4.6 Trennung eines Clients

Wenn ein Client die SSE-Verbindung beendet, wird die zugehörige Verbindung auf der Chat-Service-Instanz entfernt.

Der Server darf keine dauerhaft gespeicherte Referenz auf eine beendete SSE-Verbindung behalten.

Ein Fehler beim Schreiben auf eine SSE-Verbindung führt dazu, dass diese Verbindung geschlossen und entfernt wird.

Andere SSE-Verbindungen und andere Chat-Räume dürfen dadurch nicht beeinträchtigt werden.

## 5\. Nachholen verpasster Nachrichten

### 5.1 Cursor und Datenbankabfrage

Der Client übermittelt die ID der letzten vollständig empfangenen Nachricht über Last-Event-ID.

Diese ID wird als Cursor verwendet.

Der Server sucht zuerst die Nachricht mit dieser ID in der Datenbank.

Wenn die Cursor-Nachricht in der Datenbank gefunden wird, muss sie zum angegebenen roomId gehören. Andernfalls antwortet der Server mit HTTP 400.

Wenn die Cursor-Nachricht nicht in der Datenbank gefunden wird, prüft der Server den Ringbuffer des angegebenen Raums.

Da der Ringbuffer pro Raum geführt wird, erscheint ein Cursor aus einem anderen Raum dort als nicht gefunden.

Wenn der Cursor im Ringbuffer gefunden wird, verwendet der Server den sentAt-Wert dieser Nachricht als Zeitstempel für die weitere Datenbankabfrage.

Damit kann die Datenbankabfrage auch dann ausgeführt werden, wenn die Cursor-Nachricht selbst noch nicht in PostgreSQL gespeichert wurde.

Wird der Cursor weder in der Datenbank noch im Ringbuffer des angegebenen Raums gefunden, antwortet der Server mit HTTP 409.

Anschliessend werden alle Nachrichten aus demselben Raum abgefragt, die nach dem Cursor liegen.

Die Sortierung erfolgt aufsteigend nach sent\_at und danach nach id.

Die ID dient als Tie-Breaker, falls mehrere Nachrichten denselben Zeitstempel besitzen.

Die Datenbankabfrage verwendet die Kombination aus sent\_at und id.

### 5.2 Zeitstempel und Genauigkeit

Der Server kürzt sentAt bereits beim Erzeugen der Nachricht auf Mikrosekunden.

Dafür wird Instant.now().truncatedTo(ChronoUnit.MICROS) verwendet.

PostgreSQL speichert TIMESTAMPTZ ebenfalls mit Mikrosekunden-Genauigkeit.

Dadurch stimmen die Zeitstempel in der Live-Zustellung, im Ringbuffer und in der Datenbank überein.

Die Sortierung nach sent\_at und id ergibt damit in allen drei Bereichen dieselbe Reihenfolge.

Die Nachrichten-ID wird als Tie-Breaker verwendet, falls mehrere Nachrichten denselben Zeitstempel besitzen.

### 5.3 Übergang zum Live-Betrieb

Beim Wiederverbinden muss verhindert werden, dass zwischen dem Nachholen und dem Start der Live-Zustellung Nachrichten verloren gehen.

Dafür wird folgender Ablauf verwendet:

**Schritt 1: Verbindung anmelden und Live-Nachrichten zwischenspeichern.**

Der Server registriert die neue SSE-Verbindung zuerst für den gewünschten Chat-Raum.

Neue Live-Nachrichten werden für diese Verbindung zunächst in einem temporären Puffer zwischengespeichert.

Sie werden noch nicht direkt an den Client gesendet.

**Schritt 2: Cursor prüfen.**

Der Server prüft den Cursor in der Datenbank, wenn nötig auch im Ringbuffer.

Wenn der Cursor ungültig ist, nicht zum Raum gehört oder nicht gefunden wird, meldet der Server die Verbindung wieder ab und antwortet mit dem passenden Fehlerstatus.

Bei einem Datenbankfehler wird ebenfalls abgemeldet und HTTP 503 zurückgegeben.

Erst wenn die Cursor-Prüfung erfolgreich ist, sendet der Server HTTP 200 und beginnt mit der SSE-Ausgabe.

**Schritt 3: Ringbuffer-Schnappschuss und Datenbankabfrage erstellen.**

Der Server erstellt einen Schnappschuss der aktuell verfügbaren Nachrichten im Ringbuffer.

Zusätzlich fragt er die Nachrichten aus PostgreSQL ab, die nach dem Cursor liegen.

**Schritt 4: Nachrichten zusammenführen.**

Die Ergebnisse aus Datenbank und Ringbuffer werden zusammengeführt.

Doppelte Nachrichten werden anhand ihrer id entfernt.

Die verbleibenden Nachrichten werden nach sent\_at und id aufsteigend sortiert.

**Schritt 5: Nachgeholte Nachrichten senden.**

Der Server sendet die zusammengeführten Nachrichten an den Client.

Die Reihenfolge entspricht der definierten Sortierung.

**Schritt 6: Zwischengespeicherte Live-Nachrichten senden und in den Live-Betrieb wechseln.**

Anschliessend sendet der Server die während der Wiederherstellung zwischengespeicherten Live-Nachrichten.

Nachrichten, deren id bereits im Nachholvorgang gesendet wurde, werden übersprungen.

Danach wird die Verbindung in den normalen Live-Betrieb überführt.

Damit keine Lücke entsteht, gelten zwei wichtige Regeln:

Der Dispatcher trägt eine empfangene Nachricht zuerst in den Ringbuffer ein und verteilt sie erst danach an die SSE-Verbindungen.

Der neue Client wird zuerst für Live-Nachrichten registriert und liest erst danach den Ringbuffer-Schnappschuss und die Datenbank.

So werden Nachrichten, die während des Nachholens eintreffen, entweder im Ringbuffer oder im temporären Verbindungspuffer berücksichtigt.

Die Zusammenführung muss anhand der Nachrichten-ID deduplizieren und nach sent\_at und id sortieren.

Der temporäre Verbindungspuffer muss ebenfalls begrenzt sein. Wenn seine Grenze überschritten wird, wird die SSE-Verbindung beendet. Der Client muss sich erneut verbinden und mit Last-Event-ID einen neuen Nachholversuch starten.

### 5.4 Ringbuffer

Jede Chat-Service-Instanz besitzt einen eigenen In-Memory-Ringbuffer.

Der Ringbuffer speichert kürzlich empfangene Nachrichten nach Chat-Raum.

Die Standardgrenzen sind:

* Maximal 5000 Nachrichten pro Raum.
* Maximal 10000 Nachrichten insgesamt pro Instanz.
* Maximale Inhaltsgrösse pro Nachricht gemäss CHAT\_MAX\_CONTENT\_BYTES (siehe Abschnitt 7).

Die Grenze von 5000 Nachrichten pro Raum ermöglicht es, einen begrenzten Verlauf für aktive Räume vorzuhalten.

Die globale Grenze von 10000 Nachrichten verhindert, dass viele aktive Räume zusammen unbegrenzt Speicher belegen.

Wenn die globale Grenze überschritten wird, wird die global älteste gespeicherte Nachricht entfernt.

Die Grenze pro Raum wird unabhängig davon eingehalten.

Der Ringbuffer speichert die Nachrichten nur im Arbeitsspeicher.

Bei einem Neustart oder Absturz der Instanz gehen die Ringbuffer-Daten verloren.

Die Speichergrenze von 10000 Nachrichten und dem Standardwert von 4096 Byte Inhalt ergibt maximal ungefähr 39,1 MiB reine Nachrichteninhalte.

Der tatsächliche Speicherverbrauch in Java ist höher, da Objekte, Strings, Referenzen und Datenstrukturen zusätzlichen Speicher benötigen.

Der Ringbuffer ist deshalb eine begrenzte Wiederherstellungsmöglichkeit und kein Ersatz für PostgreSQL.

## 6\. Fehler- und Randfälle

### 6.1 Fehlende roomId

Wenn roomId fehlt, antwortet der SSE-Endpunkt mit HTTP 400.

Ohne Raum-ID kann der Server nicht bestimmen, welche Nachrichten zugestellt werden sollen.

### 6.2 Ungültige roomId

Wenn roomId keine gültige UUID ist, antwortet der SSE-Endpunkt mit HTTP 400.

### 6.3 Ungültige Last-Event-ID

Wenn Last-Event-ID keine gültige UUID enthält, antwortet der Server mit HTTP 400.

### 6.4 Cursor gehört zu einem anderen Raum

Wenn die Cursor-Nachricht in der Datenbank gefunden wird, aber nicht zum angegebenen roomId gehört, antwortet der Server mit HTTP 400.

Ein Cursor, der nur im Ringbuffer eines anderen Raums vorhanden ist, wird im Ringbuffer des angegebenen Raums nicht gefunden. Wenn er auch nicht in der Datenbank gefunden wird, antwortet der Server mit HTTP 409.

### 6.5 Cursor nicht mehr verfügbar

Wenn die Cursor-Nachricht weder in PostgreSQL noch im Ringbuffer des angegebenen Raums gefunden wird, antwortet der Server mit HTTP 409.

Der Server kann in diesem Fall nicht sicher bestimmen, welche Nachrichten seit dem Cursor fehlen.

### 6.6 PostgreSQL nicht verfügbar

Wenn PostgreSQL während eines Nachholvorgangs nicht erreichbar ist, antwortet der Server mit HTTP 503. Das gilt auch dann, wenn der Cursor im Ringbuffer vorhanden wäre.

Der Ringbuffer wird nicht als alleinige Grundlage für einen vollständigen Nachholvorgang verwendet, da er keine vollständige Historie garantiert.

Fällt PostgreSQL erst nach dem Senden von HTTP 200 aus, kann kein Fehlerstatus mehr gesendet werden. Der Server beendet in diesem Fall die Verbindung. Der Client verbindet sich mit Last-Event-ID erneut.

Eine Verbindung ohne Last-Event-ID kann weiterhin live betrieben werden, sofern RabbitMQ verfügbar ist.

### 6.7 Client trennt während des Schreibens

Wenn ein Client die Verbindung während der Übertragung beendet oder ein Schreibfehler auftritt, wird die betroffene SSE-Verbindung entfernt.

Andere Clients und Chat-Räume bleiben davon unberührt.

### 6.8 Nur ein Publish-Aufruf erfolgreich

Wenn die Veröffentlichung an chat.persist erfolgreich ist, aber chat.delivery fehlschlägt, kann die Nachricht später gespeichert werden, ohne live zugestellt worden zu sein.

Wenn chat.delivery erfolgreich ist, aber chat.persist fehlschlägt, kann die Nachricht live zugestellt werden, ohne dauerhaft gespeichert zu werden.

Da die beiden Publish-Aufrufe nicht atomar sind, kann der Server den bereits erfolgreichen Publish-Aufruf nicht zurücknehmen.

Der POST-Endpunkt antwortet in beiden Fällen mit HTTP 503.

### 6.9 RabbitMQ nicht verfügbar

Wenn RabbitMQ nicht erreichbar ist und die Nachricht nicht an beide benötigten Wege veröffentlicht werden kann, antwortet der POST-Endpunkt mit HTTP 503.

Eine Live-Zustellung kann während des Ausfalls nicht garantiert werden.

### 6.10 Queue-Limit erreicht

Wenn eine Instanz-Queue ihr Limit erreicht, werden bei drop-head die ältesten bereitliegenden Nachrichten entfernt.

Die neuesten Nachrichten bleiben bevorzugt erhalten.

Der Verlust einzelner älterer Nachrichten wird dem verbundenen Client nicht direkt gemeldet.

Ein späteres Nachholen ist nur möglich, wenn die Nachrichten in PostgreSQL oder in einem verfügbaren Ringbuffer vorhanden sind.

### 6.11 Temporärer Puffer beim Wiederverbinden voll

Wenn der temporäre Puffer einer SSE-Verbindung während des Nachholens seine Grenze erreicht, wird die Verbindung beendet.

Der Client muss sich erneut verbinden und Last-Event-ID mit der letzten vollständig empfangenen Nachrichten-ID senden.

## 7\. Konfiguration

Die folgenden Werte sind verbindliche Standardwerte. Die CHAT\_\*-Werte stehen in application.yml als `${CHAT\_…:Standardwert}` und können über Umgebungsvariablen überschrieben werden.

**CHAT\_DELIVERY\_PREFETCH:**
Standardwert 10.
Begrenzt die Anzahl unbestätigter Nachrichten pro Consumer.

**CHAT\_DELIVERY\_QUEUE\_MAX\_LENGTH:**
Standardwert 990.
Begrenzt die Anzahl bereitliegender Nachrichten pro Instanz-Queue.

**CHAT\_RINGBUFFER\_PER\_ROOM:**
Standardwert 5000.
Maximale Anzahl Nachrichten pro Chat-Raum im Ringbuffer.

**CHAT\_RINGBUFFER\_TOTAL:**
Standardwert 10000.
Maximale Anzahl Nachrichten insgesamt im Ringbuffer einer Instanz.

**CHAT\_MAX\_CONTENT\_BYTES:**
Standardwert 4096.
Maximale Grösse des Nachrichteninhalts in UTF-8-Bytes. Grössere Inhalte werden mit HTTP 400 abgelehnt und nicht abgeschnitten.

**CHAT\_SSE\_REPLAY\_BUFFER\_MAX:**
Standardwert 1000.
Maximale Anzahl Live-Nachrichten, die während des Nachholens für eine einzelne SSE-Verbindung zwischengespeichert werden. Der Wert entspricht der Grössenordnung der maximalen Queue-Grösse und begrenzt den Speicherbedarf pro Verbindung.

**CHAT\_SSE\_REPLAY\_DB\_TIMEOUT:**
Standardwert 5 Sekunden.
Maximale Dauer der Datenbankabfrage beim Nachholen. Der Wert ist kürzer als der Heartbeat-Timeout des Clients. Bei Überschreitung wird der Nachholvorgang mit HTTP 503 beendet.

**CHAT\_SSE\_HEARTBEAT\_INTERVAL:**
Standardwert 15 Sekunden.
Intervall zwischen zwei SSE-Heartbeats.

**CHAT\_SSE\_HEARTBEAT\_TIMEOUT:**
Standardwert 45 Sekunden.
Zeit ohne Heartbeat oder SSE-Event, nach der der Client die Verbindung als unterbrochen betrachtet. Der Wert wird vom Testclient beziehungsweise vom Prüfskript gelesen, nicht vom Server.

Regeln für den Client:

* Der Client überwacht jede SSE-Verbindung separat.
* Nach 45 Sekunden ohne Heartbeat oder SSE-Event gilt die Verbindung als unterbrochen.
* Der Client beendet die betroffene Verbindung.
* Der Client verbindet sich erneut und sendet Last-Event-ID mit der letzten vollständig empfangenen Nachrichten-ID.

**CHAT\_SSE\_TIMEOUT:**
Standardwert 1800000 Millisekunden.
Nach Ablauf beendet der Server die SSE-Verbindung. Der Client verbindet sich erneut und sendet Last-Event-ID mit der letzten vollständig empfangenen Nachrichten-ID.

**spring.rabbitmq.requested-heartbeat:**
Standardwert 30 Sekunden.
Legt den angeforderten RabbitMQ-Heartbeat fest.

Feste Werte (in application.yml):

* RabbitMQ-Host: `rabbitmq`
* RabbitMQ-Port: `5672`
* PostgreSQL-Host: `postgres`
* PostgreSQL-Port: `5432`

Umgebungsvariablen für RabbitMQ (bestehend):

* `RABBITMQ\_DEFAULT\_USER`
* `RABBITMQ\_DEFAULT\_PASS`

Umgebungsvariablen für PostgreSQL (neu beim Service chat-service in docker-compose.yml):

* `POSTGRES\_DB`
* `POSTGRES\_USER`
* `POSTGRES\_PASSWORD`

Der Service chat-service erhält kein depends\_on: postgres. Er muss auch dann starten und Nachrichten annehmen können, wenn PostgreSQL nicht erreichbar ist.

Die Werte und Variablen werden in `.env.example` dokumentiert. Passwörter dürfen nicht direkt im Quellcode oder in der Dokumentation gespeichert werden.

## 8\. Abnahmekriterien

### S1: Tests

Im Wurzelverzeichnis des Projekts wird folgender Befehl ausgeführt:

`mvn clean test`

Docker muss laufen, da Testcontainers echte Container startet.

Erwartetes Ergebnis:
Alle bestehenden und neu hinzugefügten Tests laufen erfolgreich durch.

### S2: Frischer Start und Compose-Konfiguration

In einem frischen Klon:

1. `.env.example` nach `.env` kopieren: `cp .env.example .env`
2. `docker compose up -d --build` ausführen.
3. `docker compose ps` prüfen.
4. `docker compose config` prüfen.

Erwartetes Ergebnis:
Alle benötigten Services starten erfolgreich. In der Ausgabe von `docker compose config` darf kein `published:` vorkommen. Es dürfen keine Host-Ports veröffentlicht werden.

### S3: Zustellung an zwei Instanzen

Das Skript `scripts/pruefe-live-zustellung.ps1` startet zwei chat-service-Instanzen mit `--scale chat-service=2`.

An jeder Instanz wird ein SSE-Client für Raum A verbunden. Ein dritter Client verbindet sich mit Raum B.

Über jede Instanz werden 200 Nachrichten für Raum A gesendet, insgesamt also 400.

Erwartetes Ergebnis:

* Beide Clients in Raum A erhalten alle 400 Nachrichten.
* Jede Nachricht wird pro Client genau einmal empfangen.
* Die Reihenfolge je Absender bleibt erhalten.
* Der Client in Raum B erhält keine Nachrichten aus Raum A.

Zusätzlich wird das Szenario mit dem Test `TwoInstancesDeliveryIntegrationTest` geprüft:

`mvn -pl backend/chat-service test -Dtest=TwoInstancesDeliveryIntegrationTest`

### S4: Queue-Lebenszyklus

Bei zwei laufenden Instanzen existieren genau zwei Instanz-Queues am Exchange chat.delivery.

Jede Queue hat genau einen Consumer.

Die Prüfung erfolgt mit:

`docker compose exec rabbitmq rabbitmqctl list\_queues name messages consumers`

und:

`docker compose exec rabbitmq rabbitmqctl list\_bindings source\_name destination\_name`

Danach wird auf eine Instanz skaliert:

`docker compose up -d --scale chat-service=1`

Erwartetes Ergebnis:

* Spätestens 30 Sekunden danach ist nur noch eine Instanz-Queue am Exchange chat.delivery vorhanden.
* Die verbleibende Queue hat genau einen Consumer.
* Die Queues chat.persist und chat.dlq sind weiterhin vorhanden.

### S5: Live-Zustellung ohne PostgreSQL und batch-writer

Für den Test wird ein neuer Raum verwendet. Der SSE-Client muss bereits verbunden sein, bevor die Nachrichten gesendet werden.

Dann werden PostgreSQL und der batch-writer gestoppt:

`docker compose stop batch-writer postgres`

Es werden 100 Nachrichten gesendet.

Erwartetes Ergebnis:

* Der verbundene Client erhält alle 100 Nachrichten innerhalb von spätestens 5 Sekunden.
* Die Live-Zustellung funktioniert trotz gestopptem PostgreSQL und batch-writer.

Danach werden die Dienste wieder gestartet:

`docker compose start postgres batch-writer`

Die Anzahl der gespeicherten Nachrichten wird nur für den verwendeten Testraum geprüft:

`docker compose exec postgres psql -U chatapp -d chat -c "SELECT count(\*) FROM message WHERE room\_id = '<Raum-UUID>'"`

Erwartetes Ergebnis: 100.

### S6: Queue-Limit bei pausierter Instanz

Es laufen zwei chat-service-Instanzen. Die Namen der Container werden mit `docker compose ps` ermittelt.

Eine Instanz wird mit `docker pause <Containername>` pausiert. Die andere Instanz bleibt aktiv.

An die aktive Instanz werden 5000 Nachrichten gesendet.

Die Queue wird mit folgendem Befehl überprüft:

`docker compose exec rabbitmq rabbitmqctl list\_queues name messages consumers`

Die Spalte messages zählt bereitliegende und unbestätigte Nachrichten. Dieser Wert muss unter 1000 bleiben.

Die Queue wird innerhalb von etwa 60 Sekunden wiederholt geprüft, damit der Heartbeat-Ausfall von RabbitMQ erkannt werden kann.

Erwartetes Ergebnis:

* Der Client an der aktiven Instanz erhält alle 5000 Nachrichten.
* Die Queue der pausierten Instanz überschreitet die Grenze von 1000 Nachrichten nicht, solange die RabbitMQ-Verbindung noch besteht und genau ein Consumer mit Prefetch 10 verwendet wird.
* Die Queue der pausierten Instanz kann nach ungefähr 60 Sekunden gelöscht werden, weil RabbitMQ bei einem Heartbeat von 30 Sekunden nach etwa zwei verpassten Heartbeats die Verbindung schliesst.

Danach wird die Instanz fortgesetzt:

`docker unpause <Containername>`

Wenn RabbitMQ die Verbindung während der Pause geschlossen hat, legt die Instanz nach dem Wiederverbinden eine neue exklusive Queue an und bindet sie an den Exchange.

Anschliessend wird eine neue Nachricht gesendet.

Erwartetes Ergebnis:
Die fortgesetzte Instanz stellt neue Nachrichten wieder zu.

### S7: Wiederverbindung und Nachholen

Ein Client öffnet eine SSE-Verbindung und empfängt eine Nachricht.

Danach trennt der Client die Verbindung.

Während der Unterbrechung werden 50 weitere Nachrichten gesendet.

Nach 10 Sekunden verbindet sich der Client erneut und sendet Last-Event-ID mit der ID der zuletzt vollständig empfangenen Nachricht.

Erwartetes Ergebnis:

* Der Client erhält genau die 50 fehlenden Nachrichten.
* Die Nachrichten kommen in der richtigen Reihenfolge an.
* Keine der 50 Nachrichten wird doppelt zugestellt.
* Nach dem Nachholen funktioniert die normale Live-Zustellung wieder.

Das Szenario wird mit dem Skript `scripts/pruefe-live-zustellung.ps1` am laufenden Stack geprüft und mit dem Test `ReconnectIntegrationTest`:

`mvn -pl backend/chat-service test -Dtest=ReconnectIntegrationTest`

Der Test prüft das Szenario in zwei Varianten: mit Nachrichten, die bereits in PostgreSQL stehen, und mit Nachrichten, die nur im Ringbuffer liegen.

### S8: Code- und Konfigurationsprüfung

Folgender Befehl wird ausgeführt:

`git ls-files .env`

Erwartetes Ergebnis:
Die Ausgabe ist leer. Die Datei .env ist nicht versioniert.

Im Quellcode von chat-service, einschliesslich der Tests, wird nach folgenden Ausdrücken gesucht:

* `.stream(`
* `Stream<`
* `.map(`
* `.filter(`
* `.collect(`

Die Suche erfolgt im Verzeichnis `backend/chat-service/src`.

Erwartetes Ergebnis:
Die Suche ergibt keine Treffer.

Zusätzlich wird von Hand geprüft, ob über jeder Klasse und jeder Methode ein passender Kommentar vorhanden ist.

## Zusätzliche Prüfungen

Die folgenden Prüfungen ergänzen die PDF-Abnahmekriterien. Sie sind zusätzliche technische Tests. Die Befehle werden im Wurzelverzeichnis des Projekts ausgeführt, Docker muss laufen (siehe S1).

### Veröffentlichung auf beiden Wegen

Testklasse: `PublishBothWaysIntegrationTest`

Testbefehl:

`mvn -pl backend/chat-service test -Dtest=PublishBothWaysIntegrationTest`

Erwartetes Ergebnis:
Die Nachricht in chat.persist und die Nachricht in einer an chat.delivery gebundenen Test-Queue haben identisches JSON.

### Ausfall von RabbitMQ beim Veröffentlichen

Testklasse: `PublishFailureIntegrationTest`

Testbefehl:

`mvn -pl backend/chat-service test -Dtest=PublishFailureIntegrationTest`

Erwartetes Ergebnis:
Eine AMQP-Ausnahme führt zu HTTP 503.

### S9: Begrenzung der Nachrichtengrösse

Testklasse: `MessageSizeLimitIntegrationTest`

Testbefehl:

`mvn -pl backend/chat-service test -Dtest=MessageSizeLimitIntegrationTest`

Es wird eine Nachricht gesendet, deren Inhalt CHAT\_MAX\_CONTENT\_BYTES überschreitet.

Erwartetes Ergebnis:

* Der POST-Endpunkt antwortet mit HTTP 400.
* Der Inhalt wird nicht abgeschnitten.
* Die Nachricht wird weder an chat.persist noch an chat.delivery veröffentlicht.

### S10: Zeitstempelgenauigkeit

Testklasse: `SentAtPrecisionTest`

Testbefehl:

`mvn -pl backend/chat-service test -Dtest=SentAtPrecisionTest`

Eine neue Nachricht wird erzeugt und veröffentlicht.

Der Wert sentAt wird überprüft.

Erwartetes Ergebnis:

* sentAt besitzt keine Nanosekunden-Anteile unterhalb der Mikrosekundengrenze.

### S11: Identisches Nachrichtenformat nach dem Nachholen

Testklasse: `ReplayJsonFormatIntegrationTest`

Testbefehl:

`mvn -pl backend/chat-service test -Dtest=ReplayJsonFormatIntegrationTest`

Eine Nachricht wird veröffentlicht und anschliessend aus PostgreSQL gelesen.

Aus der Datenbankzeile wird ein NewMessageRequest erstellt.

Dieses Objekt wird mit demselben Jackson-Converter serialisiert, der auch beim Publish im chat-service verwendet wird.

Der JSON-Text der aus der Datenbank gelesenen Nachricht wird mit dem JSON-Text der ursprünglich veröffentlichten Nachricht verglichen.

Erwartetes Ergebnis:
Beide JSON-Texte sind identisch.

## 9\. Bekannte Einschränkungen

Die Live-Zustellung garantiert keine Exactly-once-Zustellung.

Durch Wiederverbindungen und parallele Verarbeitung können Nachrichten erneut auftreten. Der Client beziehungsweise der Server muss Duplikate anhand der Nachrichten-ID erkennen können.

Wenn ein Client einen Request nach HTTP 503 wiederholt, erzeugt der Server eine neue Nachrichten-ID. Falls der erste Request bereits an chat.persist veröffentlicht wurde, kann dieselbe Nachricht dadurch ein zweites Mal mit einer anderen ID gespeichert werden. Diese Duplikate lassen sich nicht anhand der ID erkennen.

Die Queue verwendet drop-head. Bei Überlastung können deshalb ältere Nachrichten verloren gehen.

Der Ringbuffer ist nur im Arbeitsspeicher vorhanden. Bei einem Neustart oder Absturz gehen seine Daten verloren.

Der Ringbuffer ist begrenzt und ersetzt keine vollständige Nachrichtenhistorie.

Wenn ein Client länger unterbrochen ist und der Cursor nicht mehr in der Datenbank oder im Ringbuffer des angegebenen Raums vorhanden ist, kann der Server die fehlenden Nachrichten nicht nachliefern.

Die Zeitstempel werden serverseitig erzeugt und auf Mikrosekunden gekürzt. Bei unterschiedlichen Systemzeiten der Chat-Service-Instanzen kann die chronologische Reihenfolge zwischen Instanzen trotzdem beeinflusst werden.

Die Sortierung nach sent\_at und id stellt eine eindeutige Reihenfolge her, garantiert aber keine globale Reihenfolge der tatsächlichen Ereignisse.

Die beiden Publish-Aufrufe an chat.persist und chat.delivery sind nicht atomar. Bei einem teilweisen Fehler kann eine Nachricht bereits über einen der beiden Wege verarbeitet worden sein.

Publisher Confirms sind nicht aktiviert. Ein erfolgreicher Publish-Aufruf bestätigt deshalb nicht automatisch, dass die Nachricht dauerhaft in der Queue gespeichert wurde.

Wenn eine Instanz länger pausiert ist, kann RabbitMQ die Verbindung nach etwa 60 Sekunden schliessen. Dieser Wert ergibt sich aus dem Heartbeat von 30 Sekunden und dem Schliessen nach ungefähr zwei verpassten Heartbeats.

Nach dem Schliessen der Verbindung wird die exklusive Queue entfernt. Nachrichten, die sich nur in dieser Queue befanden, können dadurch verloren gehen.

Die Live-Zustellung ist deshalb eine schnelle Zustellmöglichkeit und keine dauerhafte oder verlustfreie Nachrichtenübertragung.

## 10\. Belegte Projektfakten und verbleibende Prüfungen

Folgende Projektfakten sind bekannt:

* Das DTO NewMessageRequest verwendet UUID für id und roomId, String für senderId und senderName sowie Instant für sentAt.
* Die PostgreSQL-Tabelle heisst message.
* Die PostgreSQL-Spalten und Typen entsprechen der Feldzuordnung in Abschnitt 2.1.
* Das tatsächliche JSON-Format wurde an einer echten Nachricht aus chat.persist geprüft.
* sentAt wird als Zahl mit Sekunden seit 1970 und Nachkommastellen übertragen.
* chat.persist ist eine Queue am Standard-Exchange, kein eigener Exchange.
* Die Anwendung verwendet Spring Boot 3.3.5.
* Publisher Confirms sind in der bestehenden Konfiguration nicht gesetzt.
* Der bestehende RabbitMQ-Heartbeat beträgt standardmässig 60 Sekunden. Für die neue Live-Zustellung wird er auf 30 Sekunden gesetzt.
* Die Queue chat.persist ist dauerhaft (durable).
* Nachrichten werden standardmässig persistent (Spring-AMQP-Standard) veröffentlicht.
* chat.persist behält Nachrichten auch bei gestopptem batch-writer.
* Der RabbitMQ-Host lautet rabbitmq.
* Der RabbitMQ-Port lautet 5672.
* Der PostgreSQL-Host für die neue Verbindung lautet postgres.
* Der PostgreSQL-Port lautet 5432.
* Die Service-Namen postgres, rabbitmq, chat-service und batch-writer stimmen mit docker-compose.yml überein.
* Das Image des chat-service enthält BusyBox-wget.

Dass pro Instanz-Queue genau ein Consumer aktiv ist, wird in S4 geprüft, sobald der Consumer implementiert ist.

