# Keycloak

`realm-export.json` ist ein **minimaler Platzhalter** fuer die lokale
Entwicklung, kein produktionsreifer Realm:

- `redirectUris: ["*"]` und `webOrigins: ["*"]` sind fuer die Entwicklung
  bequem, aber unsicher — vor echtem Einsatz auf die tatsaechliche
  Frontend-Origin einschraenken.
- Es sind noch keine Test-Benutzer angelegt.
- `KC_HOSTNAME_STRICT: "false"` in der docker-compose.yml lockert die
  Hostname-Pruefung fuer die lokale Entwicklung. Sobald das Frontend als
  Reverse-Proxy vor Keycloak steht (siehe ARCHITECTURE.md, Punkt 3), sollte
  `KC_HOSTNAME` explizit auf die aeussere URL gesetzt werden, damit die
  "iss"-Angabe im JWT mit der URL uebereinstimmt, die die Backend-Services
  zur Pruefung verwenden.
