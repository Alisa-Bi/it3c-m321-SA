# Architekturentscheidungen — Backend-Grundgerüst

Dieses Dokument begründet die Entscheidungen, die beim Aufbau des
Backend-Grundgerüsts getroffen wurden, insbesondere dort, wo die Planung noch
offene Punkte hatte.

## 1. API Gateway: Spring Cloud Gateway statt Nginx

Offener Punkt aus der Planung: "Spring Cloud Gateway oder Nginx als Gateway?"

Entscheidung: **Spring Cloud Gateway.**

Begründung: Das Modul ist ein Java-Backend-Kurs — Routing-Regeln und die
JWT-Prüfung bleiben damit in derselben Sprache und demselben Framework wie
der Rest des Backends (Spring Security, Spring Boot Testing, dieselbe
Log-Konfiguration). Die Routen sind in `application.yml` deklarativ und in
zwei Sätzen erklärbar ("Pfad X geht zu Service Y"). Nginx wäre eine
zusätzliche Technologie mit eigener Konfigurationssprache, die die Klasse
zusätzlich lernen müsste, ohne einen didaktischen Mehrwert für dieses Modul.
Nginx kommt trotzdem zum Zug — siehe Punkt 3.

## 2. Datenbank: eine Instanz, aber eine Datenbank pro Service

Offener Punkt aus der Planung: "Eine gemeinsame Datenbank oder Datenbank pro
Service?"

Entscheidung: **Eine PostgreSQL-Instanz, aber pro Service eine eigene
Datenbank** (`user_db`, `chat_db`), angelegt über
`infrastructure/postgres/init.sql`.

Begründung: Microservices sollen ihre Daten nicht teilen — sonst entsteht über
die gemeinsame Datenbank eine versteckte Kopplung, die alle Vorteile der
Microservice-Architektur zunichtemacht. Eine eigene Datenbank pro Service
erzwingt das: `user-service` kann technisch nicht auf `messages` zugreifen.
Gleichzeitig bedeutet das *keine eigene Instanz pro Service* — das wäre für
den Unterrichtsrahmen (Ressourcen, Betriebsaufwand) unverhältnismässig. Diese
Lösung ist ein bewusster Mittelweg, kein Widerspruch.

## 3. Wie passt "nur das Frontend veröffentlicht einen Port" zum Gateway?

Die Planung listet das API-Gateway explizit unter "nur intern erreichbar".
Das bedeutet: **Der Browser spricht nie direkt mit dem API-Gateway.**

Auflösung: Der Frontend-Container (sobald er existiert) läuft hinter einem
eigenen nginx, der zwei Aufgaben übernimmt:

1. Die gebaute React-App ausliefern.
2. Alle Anfragen an `/api/**` und `/ws/**` intern an `api-gateway:8080`
   weiterleiten (Reverse Proxy), ebenso `/realms/**` an `keycloak:8080`, damit
   der Login-Redirect und die spätere Token-Validierung dieselbe Herkunfts-URL
   sehen (siehe Punkt 5).

Nur dieser eine Container veröffentlicht Port 80 nach `localhost`. Das ist in
`docker/docker-compose.yml` als auskommentierter `frontend`-Dienst
vorbereitet, da das Frontend nicht Teil dieses Backend-Grundgerüsts ist.

## 4. Kein gemeinsamer Parent-POM für die drei Services

Entscheidung: **Jeder Service hat seinen eigenen, vollständig eigenständigen
`pom.xml`** mit `spring-boot-starter-parent` als Parent — kein
Reactor-/Aggregator-POM über allen dreien.

Begründung: Microservices sollen unabhängig voneinander gebaut, versioniert
und deployt werden können. Ein gemeinsamer Parent-POM wäre eine
"Vorrats-Abstraktion" (siehe `CLAUDE.md`) für ein Problem, das noch nicht
existiert — aktuell teilen die drei Services keine Bibliothekscode. Sollte
später z. B. ein gemeinsames DTO für Events entstehen, wäre ein eigenes
kleines "shared"-Modul die richtige Lösung, kein Parent-POM.

## 5. Bewusste Lücke: WebSocket-Endpunkt ist noch nicht abgesichert

`/ws/chat` ist in der `SecurityConfig` des chat-service aktuell mit
`permitAll()` versehen, mit einem TODO-Kommentar im Code.

Begründung: Browser-WebSockets können beim Verbindungsaufbau keine
`Authorization`-Header setzen. Die gängige Lösung (Token als
Query-Parameter, geprüft in einem `HandshakeInterceptor`) hängt vom
Frontend ab, das es noch nicht gibt. Nach der Projektregel "Nichts
behaupten, was nicht geprüft wurde" wird hier keine Schein-Sicherheit
vorgetäuscht, sondern die Lücke offen im Code dokumentiert.

## 6. `ddl-auto: update` statt Flyway/Liquibase

Für das Grundgerüst erzeugt Hibernate das Schema automatisch. Für den
Unterrichtszweck ist das ausreichend und hält das Grundgerüst schlank. Vor
produktivem Einsatz oder sobald mehrere Personen parallel am Schema
arbeiten, sollte ein Migrationswerkzeug (Flyway) eingeführt werden, damit
Schemaänderungen nachvollziehbar und reproduzierbar sind.

## 7. `ChatMember` mit technischem Schlüssel statt zusammengesetztem Schlüssel

Die Planung modelliert `ChatMember` nur über `roomId` + `userId` (kein `id`).
Für das Grundgerüst wurde zusätzlich ein technischer `id`-Schlüssel (UUID)
ergänzt, statt eines zusammengesetzten JPA-Schlüssels (`@EmbeddedId` oder
`@IdClass`). Das spart Boilerplate-Code (eigene Key-Klasse) und ist für
Einsteiger leicht nachvollziehbar. Eine eindeutige Kombination aus `roomId`
und `userId` lässt sich bei Bedarf später über einen `UNIQUE`-Constraint
erzwingen.

## 8. Senden und Speichern von Nachrichten sind entkoppelt

`POST /api/messages` speichert **nicht** direkt in der Datenbank. Stattdessen
prüft `MessageService.publishNewMessage(...)` nur die Eingabe und übergibt
sie über den `MessageProducer` an RabbitMQ; die eigentliche Speicherung
passiert ausschliesslich im `MessageConsumer`, der die Nachricht aus der
Queue liest und `MessageService.createMessage(...)` aufruft. Der Endpunkt
antwortet deshalb mit `202 Accepted`, nicht mit der fertig gespeicherten
Nachricht.

Begründung: Das entspricht exakt dem in der Planung dokumentierten
Nachrichtenfluss (Chat Service → RabbitMQ → Message Consumer → Speicherung).
Es entkoppelt den Schreibpfad vom Antwortpfad - der Sender wartet nicht auf
den Datenbank-Schreibvorgang - und ist die Grundlage dafür, dass später
mehrere Instanzen des Chat-Service dieselbe Queue konsumieren können, ohne
dass REST-Handler und Persistenz aneinander gekoppelt sind. Wer die
gespeicherte Nachricht sehen will, ruft `GET /api/messages/{roomId}` auf
oder erhält sie (sobald implementiert) per WebSocket-Broadcast.

`ChatRoom`-Erstellung bleibt bewusst synchron und geht direkt in die
Datenbank: Chaträume werden selten und nicht unter Lastspitzen angelegt,
eine Entkopplung über RabbitMQ wäre hier unnötige Komplexität.

## 9. Umsetzung der Code-Stil-Regeln aus `CLAUDE.md`

- **Records für DTOs** (`UserDto`, `ChatRoomDto`, `MessageDto`, …), normale
  Klassen mit Lombok (`@Getter @Setter`) für JPA-Entitäten, da Records als
  unveränderliche Objekte nicht gut zu Hibernates Anforderungen
  (leerer Konstruktor, veränderliche Felder) passen.
- **Keine Stream-Ketten**, stattdessen `for`-Schleifen beim Mapping von
  Listen (z. B. `UserService.searchUsers`), wie in `CLAUDE.md` gefordert.
- **`@Slf4j` und `@RequiredArgsConstructor`** überall dort, wo eine Klasse
  einen Logger bzw. injizierte Abhängigkeiten braucht.
- **Keine Interfaces mit nur einer Implementierung**: `UserMapper`,
  `ChatRoomMapper`, `MessageMapper` sind normale Klassen, keine
  Interface+Impl-Paare. `JpaRepository`-Interfaces sind eine Ausnahme, da sie
  ein Standard-Spring-Data-Muster sind, dessen Implementierung das Framework
  automatisch generiert.
- **Kommentare auf Deutsch, Code auf Englisch** wie vorgegeben.
