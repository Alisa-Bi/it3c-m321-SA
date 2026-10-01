# Chat-App — Backend-Grundgerüst

Dieses Repository enthält das Backend-Grundgerüst für die M321-Chat-App (IT3c).
Es ist bewusst ein **Grundgerüst**: kompilierbar und startbar, aber ohne
vollständige Fachlogik (siehe `ARCHITECTURE.md` für die Begründungen).

## Struktur

```text
chat-app/
├── docker/                Docker-Compose-Setup für die gesamte Architektur
├── backend/
│   ├── api-gateway/        Einziger Einstiegspunkt ins Backend, prüft JWTs
│   ├── user-service/       Benutzerprofile und -suche
│   └── chat-service/       Chaträume, Nachrichten, RabbitMQ, WebSocket
├── infrastructure/
│   ├── keycloak/           Minimaler Realm-Export für die lokale Entwicklung
│   └── postgres/           Init-Skript (legt die Datenbank pro Service an)
├── frontend/               Platzhalter — folgt in einem separaten Sprint
├── ARCHITECTURE.md         Begründung der Architekturentscheidungen
└── .env.example            Beispielwerte für lokale Umgebungsvariablen
```

## Starten

Voraussetzung: Docker und Docker Compose.

```bash
cp .env.example .env        # Passwörter für die lokale Entwicklung anpassen
cd docker
docker compose up --build
```

Wichtig: Laut Projektregel (`CLAUDE.md`) veröffentlicht **nur das Frontend**
einen Port nach außen. Gateway, Services, Postgres, RabbitMQ und Keycloak sind
daher *nicht* direkt vom Host aus erreichbar — das ist Absicht, kein Fehler.
Zum lokalen Debuggen (z. B. RabbitMQ-Management-UI oder DBeaver-Zugriff auf
Postgres) legt jede Person bei Bedarf eine eigene, nicht eingecheckte
`docker/docker-compose.override.yml` mit zusätzlichen Port-Mappings an
(steht in `.gitignore`).

## Wichtige offene Punkte

Siehe auch `ARCHITECTURE.md` für die Details und Begründungen:

- Keycloak-Realm ist ein minimaler Platzhalter mit unsicheren Dev-Einstellungen
  (z. B. `redirectUris: ["*"]`) — vor echtem Einsatz härten.
- Der WebSocket-Endpunkt `/ws/chat` ist noch **nicht** durch ein JWT abgesichert
  (bewusst offen, siehe `SecurityConfig` im chat-service, mit TODO-Kommentar).
- Schema-Erzeugung läuft aktuell über `ddl-auto: update` — für den Unterricht
  in Ordnung, vor produktivem Einsatz durch Flyway oder Liquibase ersetzen.
- Spring-Boot- und Spring-Cloud-Versionen vor Projektstart auf Aktualität
  prüfen (`mvn versions:display-dependency-updates`).

## Kompilieren

Der Sandbox-Container, in dem dieses Grundgerüst erzeugt wurde, hatte keinen
Netzwerkzugriff auf Maven Central. Bitte in jedem Service einmal lokal
`mvn -q clean package` ausführen und prüfen, bevor weiterentwickelt wird
(Projektregel: "Nichts behaupten, was nicht geprüft wurde").
