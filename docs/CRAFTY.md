# Crafty Controller einrichten

Crafty ist **optional**. Mit `crafty.enabled: false` nutzt ScamUpdates den lokalen Neustart (`/restart`, siehe unten) und verlangt keine Crafty-Angaben. Mit `crafty.enabled: true` müssen alle Angaben in der `config.yml` von dir manuell eingetragen werden; ScamUpdates rät nichts und erstellt keine Tokens. Sind Angaben fehlerhaft oder ist Crafty nicht erreichbar, wird ausschließlich die Crafty-Integration deaktiviert — alle übrigen Plugin-Funktionen bleiben aktiv.

Stand: Crafty Controller 4.x. Menübezeichnungen können je nach Version abweichen, maßgeblich ist https://docs.craftycontrol.com.

## Benötigte Werte

| Einstellung | Bedeutung |
|---|---|
| `crafty.connection.base-url` | Adresse der Crafty-Weboberfläche ohne `/panel/...`, standardmäßig `https://<host>:8443`. Muss vom Minecraft-Server erreichbar sein (bei Docker nicht `localhost`, sondern Hostname/IP des Containers). |
| `crafty.connection.api-base-path` | `/api/v2` (relativ zur Adresse). Nur ändern, wenn deine Crafty-Version etwas anderes nennt. |
| `crafty.connection.api-token` | API-Key eines Crafty-Benutzers. Anlegen: Benutzername oben rechts → Benutzer-Konfiguration → Bereich „API Keys“ → neuen Key erzeugen und sofort kopieren (er wird nur einmal angezeigt). Nie weitergeben. |
| `crafty.connection.server-id` | UUID des Servers: Server in Crafty öffnen, in der Adresszeile steht `…/panel/server_detail?id=<UUID>`. |

**Berechtigungen:** Der Benutzer hinter dem Token braucht Zugriff auf genau diesen Server mit dem Recht, ihn zu starten, zu stoppen und neu zu starten. Ein Issue im Crafty-Tracker berichtet, dass Start/Stop mit einem Key, der nur das Recht „Commands“ hat, mit HTTP 400 scheitern kann. Reicht „Commands“ bei dir nicht, gib dem Key (bzw. dem Benutzer) weitergehende Rechte auf diesen einen Server, aber nicht mehr als nötig. Der Token steht im Klartext in der `config.yml`: Dateirechte einschränken (`chmod 600`) und die Datei nie veröffentlichen.

## Verbindung testen

```
curl -k -H "Authorization: Bearer DEIN_TOKEN" https://crafty.example.com:8443/api/v2/servers/DEINE_SERVER_ID
```

(`-k` überspringt nur beim Test die Zertifikatsprüfung.) Erwartet: HTTP 200 mit `"status":"ok"`. HTTP 401/403: Token falsch oder ohne Rechte. HTTP 404: Server-ID oder API-Pfad falsch. Keine Antwort: Adresse, Port, Firewall prüfen. Danach `/scamupdates config reload` und `/scamupdates status` (Soll: `ACTIVE`).

## SSL

* **Empfohlen:** gültiges Zertifikat über Reverse-Proxy (z. B. Caddy, Nginx Proxy Manager, Traefik mit Let's Encrypt). `verify-ssl: true`, `ca-certificate-file` leer.
* **Selbst signiert:** Zertifikat (PEM) auf den Minecraft-Server kopieren und in `ca-certificate-file` eintragen. Der Hostname/die IP aus der `base-url` muss im Zertifikat als „Subject Alternative Name“ stehen. Mit dieser Datei vertraut ScamUpdates für die Crafty-Verbindung nur diesen Zertifikaten.
* **Nicht empfohlen:** `verify-ssl: false` deaktiviert die Prüfung; der Token kann dann im Netzwerk abgefangen werden.

## Verwendete API-Aufrufe

Alle mit `Authorization: Bearer <Token>`:

* Startprüfung: `GET <base-url>/api/v2/servers/<server-id>`
* Neustart: `POST <base-url>/api/v2/servers/<server-id>/action/restart_server`

Diese Endpunkte wurden über Open-Source-Crafty-Clients und den Crafty-Issue-Tracker gegengeprüft, nicht über die offizielle Swagger-Seite.

## Ohne Crafty: lokaler Neustart (`crafty.enabled: false`)

ScamUpdates führt den Minecraft-Befehl `/restart` aus (Name in `local-restart.command`). Der Befehl startet den Server nur dann neu, wenn in der `spigot.yml` unter `settings.restart-script` ein **vorhandenes Skript** steht (Standard `./start.sh`); sonst fährt er nur herunter. ScamUpdates prüft das beim Start und vor jedem Neustart. Fehlt der Mechanismus, erscheint eine Fehlermeldung, Updates gelten dann nur als *vorbereitet*, und es wird nie ein Neustart vorgetäuscht.

Beispiel `start.sh` (Linux, ausführbar machen mit `chmod +x start.sh`):

```
#!/bin/sh
java -Xms2G -Xmx2G -jar paper.jar --nogui
```

Hinweis: Ein `/restart` lädt die Paper-JAR beim neuen Start neu, ersetzt sie aber nicht. Für Paper-Versionswechsel ist ein separater Austausch-Mechanismus nötig (kommt mit dem Serverupdate-Abschnitt).
