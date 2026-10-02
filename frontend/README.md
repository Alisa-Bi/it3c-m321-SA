# Frontend — folgt später

Dieser Ordner ist bewusst leer. Das Frontend (React, TypeScript, Vite,
Material UI) ist nicht Teil dieses Backend-Grundgerüsts.

Sobald es existiert, übernimmt sein nginx-Container zusätzlich die Rolle
des Reverse-Proxys: Er liefert die gebaute React-App aus und leitet
`/api/**`- und `/ws/**`-Anfragen intern an den `api-gateway`-Container weiter
(sowie `/realms/**` an `keycloak`). Nur dieser Frontend-Container
veröffentlicht einen Port nach außen (siehe `docker/docker-compose.yml`,
auskommentierter `frontend`-Dienst, und `ARCHITECTURE.md`, Punkt 3).
