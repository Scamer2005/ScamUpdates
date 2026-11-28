# ScamUpdates

Update-Manager für Paper **1.20 bis 1.21.x**: erkennt installierte Plugins, sucht Updates (Modrinth, Hangar, SpigotMC/Spiget, GitHub Releases, Jenkins, direkte Links), lädt sie geprüft nach `plugins/update/`, sichert die alte JAR in `plugins/update-backup/`, bietet Rollback, ein Menü (GUI) und löst Neustarts über **Crafty Controller** oder lokal per `/restart` aus.

> ## Status (bitte lesen)
> In Schritt 1 wurden Build-System (Gradle-Wrapper 9.2.0, JDK 25, Java-17-Bytecode) und Basis-Tests verifiziert. In Schritt 2 wurden die **Crafty-Isolation** (Crafty komplett optional und fehlerisoliert), die saubere Trennung des **Offline-Betriebs** (`ACTIVE_OFFLINE`) sowie die Grundlagen fuer Warteschlange, Einzel-Updates und Neustart-Planung umgesetzt. In Schritt 3 wurden die vollständige **Update-Warteschlange** (`queue.yml` + GUI/Aktionen), die **Einzelplugin-Verwaltung** (Plugin-Bibliothek, Detailansicht, Kandidatenwahl, `downloadSingle`), die **wiederkehrenden geplanten Neustarts** (`Europe/Berlin`, DST-sicher) und **einmalige Neustarts** (Presets, benutzerdefinierte H/M/S-Eingabe, Live-Vorschau, Wiederaufnahme nach Neustart) vollständig implementiert und per JUnit 5 getestet. Auf einem echten Paper-Server (`1.20`–`1.21.x`) noch **nicht** getestet sind: Live-GUI-Rendering, Amboss-Eingabe/Bestätigung, echter `/restart` und Live-Neustarts.

## Neu in diesem Stand

| Bereich | Stand |
|---|---|
| Versionsbereich 1.20–1.21.x mit Kompatibilitätsstatus (`getestet`/`ungetestet`/`zu alt`/`zu neu`) | gebaut & per JUnit 5 geprüft (ohne Live-Server) |
| Crafty **optional & isoliert** (`crafty.enabled: false` → lokal `/restart`; Crafty-Fehler deaktivieren nur Crafty, nie das Hauptplugin) | umgesetzt & per JUnit 5 (Mock-Server) getestet |
| **Offline-Betrieb (`ACTIVE_OFFLINE`)**: lokale Befehle/Backups/Restore/Neustart/Warteschlange/Zeitpläne bleiben nutzbar; Online-Funktionen erholen sich automatisch | umgesetzt & per JUnit 5 getestet |
| **Update-Warteschlange (`queue.yml`, `QueueService`)**: Vormerken, Start-/Zyklus-Prüfung, **Jetzt aktualisieren** / **Später** / **Nicht mehr erinnern**, Mehrdeutigkeiten mit **Keins davon**, Fehlerschutz | umgesetzt & per JUnit 5 getestet |
| **Einzelplugin-Verwaltung**: Plugin-Bibliothek (Seiten, Amboss-Suche, Filter, Sortierung), Detailansicht, `downloadSingle`, Quellenwahl, Ausschluss, Backup-Restore | umgesetzt & per JUnit 5 getestet (ohne Live-GUI) |
| **Geplante & einmalige Neustarts (`restarts.yml`, `RestartCoordinator`)**: Stündlich/Täglich/Wöchentlich/Mehrere Tage in `Europe/Berlin`, Konfliktprüfung, 7 Presets (inkl. `Sofort`), Custom H/M/S mit Vorschau, konfigurierbare Warnzeiten | umgesetzt & per JUnit 5 getestet (ohne Live-GUI) |
| Kompakte `config.yml`, `gui.yml`, Migration (inkl. Kommentar-Aktualisierung) | gebaut & per JUnit 5 getestet |
| Vorgangsverwaltung (IDs, Status, Doppelstart-Schutz, neustartsicher) | gebaut & per JUnit 5 getestet |
| Update-Protokoll (`history.jsonl`, Aufbewahrung, Token-Schwärzung) | gebaut & per JUnit 5 getestet |
| Paper-Prüfung über den PaperMC-Downloaddienst (Fill v3) für Status und Update-Check | gebaut & Parsing per JUnit 5 getestet |
| GUI-System: Hauptmenü, Update-Check-Menü, Ergebnisliste, Bibliothek, Detailansicht, Kandidatenwahl, Warteschlange, Zeitpläne/Editor, Einmaliger Neustart, Protokoll-Menü | kompiliert & Logik getestet; Live-Optik auf Paper-Server ungetestet |
| Einmalcode per Amboss-Fenster (Chat-Fallback) für Bestätigungen | Logik per JUnit 5 getestet; Amboss-UI ungetestet |
| `/update` = `/scamupdates`, Permissions (`update.single`, `queue`, `schedule`, …), `history` | kompiliert; Ingame-Verhalten ungetestet |

**Noch nicht umgesetzt** (folgt erst nach Freigabe von Schritt 4): Gesamtes Serverupdate (Paper + Plugins in einem Vorgang), Paper-JAR-Austausch, Backup-Übersichts-GUI und Einstellungs-GUI. Diese Bereiche sind noch nicht aktiv; bestehende CLI-Befehle (`backup list`, `backup delete`, `restore`, `config reload`) bleiben normal nutzbar.

## Bauen

Build über den mitgelieferten Gradle-Wrapper (`./gradlew clean test build` unter Linux/macOS bzw. `gradlew.bat clean test build` unter Windows) mit **JDK 25**. Ergebnis: `build/libs/ScamUpdates.jar` (Kopie in `dist/ScamUpdates.jar`). Bytecode-Ziel ist **Java 17** (`options.release = 17`), Abhängigkeit `paper-api:1.21.11-R0.1-SNAPSHOT` (`compileOnly`).

## Version, Java, Linux

* **Unterstützt:** Minecraft 1.20 bis 1.21.x. Neuere Versionen (1.22, 26.x) und ältere als 1.20 führen **nicht** zum Absturz; ScamUpdates läuft weiter, zeigt aber den Status „nicht unterstützt“ (Log, `/scamupdates status`, Menü).
* **„Getestet“** wird nur angezeigt, wenn du die Version unter `compat.tested-versions` einträgst. Der Code behauptet von sich aus keine getestete Unterstützung.
* **Versionsabhängiges** ist isoliert: Plugin-Metadaten (`PluginMeta` oder `PluginDescriptionFile`, nur eine Variante wird geladen), Item-Flags (per `valueOf`), Amboss-Text (Reflection auf `AnvilView`/`AnvilInventory`). Gemeinsamer Code greift nie auf `InventoryView` zu (Klasse/Interface-Wechsel in 1.21), sondern vergleicht nur Inventar-Instanzen. `plugin.yml` deklariert `api-version: '1.20'`.
* **Java-Kompatibilität:** Gebaut wird mit **JDK 25**, der erzeugte Bytecode hat jedoch **Java-17-Ziel** (`class file major version 61`), sodass Paper 1.20–1.20.4 (Java 17), Paper 1.20.5–1.21.x (Java 21) und Java-25-Server gleichermaßen unterstützt werden. Ein Praxistest sehr alter Paper-1.20-Builds unter Java 25 steht noch aus.
* Linux: nichts Besonderes nötig. Für den lokalen Neustart muss das `restart-script` aus der `spigot.yml` existieren.

## Crafty Controller optional

* `crafty.enabled: false`: **kein** Fehlerzustand, **keine** Crafty-Verbindung, **keine** API-Anfragen oder Authentifizierungsversuche und **keine** Crafty-Fehlermeldungen. Alle übrigen Plugin-Funktionen bleiben verfügbar; Neustarts (nach Updates und manuell) laufen über den lokalen Minecraft-Befehl `/restart` (`local-restart.command`), sofern korrekt eingerichtet.
* `crafty.enabled: true` (Standard): Verbindung, Server-ID und Token werden beim Start asynchron geprüft; Neustarts laufen über die Crafty-API. Einrichtung: [`docs/CRAFTY.md`](docs/CRAFTY.md).
* **Fehlerhafte Crafty-Konfiguration / Ausfall:** Sind URL, API-Token, Server-ID, Timeout, SSL oder Authentifizierung (`401`/`403`) fehlerhaft oder ist Crafty nicht erreichbar, wird **ausschließlich die Crafty-Integration deaktiviert**. Das Hauptplugin bleibt aktiv (`ACTIVE` bzw. `ACTIVE_OFFLINE`), alle lokalen und Online-Updatefunktionen bleiben nutzbar, und Admins (`scamupdates.notify`) erhalten eine klare Statusmeldung ohne Offenlegung von Zugangsdaten.
* **Wichtig zu `/restart`:** Der lokale Befehl startet nur neu, wenn in der `spigot.yml` unter `settings.restart-script` ein **vorhandenes Skript** steht; sonst fährt er den Server nur herunter. ScamUpdates prüft das beim Start und vor jedem Neustart. Fehlt der Mechanismus: Fehlermeldung in Konsole und an Admins (`scamupdates.notify`), und Updates gelten dann nur als **vorbereitet** (Warnung nach dem Download, Protokolleintrag `WARN`). Ein Neustart wird nie vorgetäuscht.
* `/restart` lädt die Paper-JAR beim neuen Start nur neu, ersetzt sie aber nicht. Für Paper-Versionswechsel braucht es einen Austausch-Mechanismus (Teil des späteren Serverupdate-Abschnitts).
* Internetprüfung und alle Sicherheitsprüfungen sind vollständig unabhängig von Crafty.

## Offline-Betrieb (`ACTIVE_OFFLINE`)

* Fehlende Internetverbindung beim Start oder während des Betriebs deaktiviert ScamUpdates **nicht** pauschal, sondern versetzt es in den Zustand `ACTIVE_OFFLINE`.
* **Offline weiter nutzbar:** `status`, `list` (letzter bekannter Snapshot), `history`, `backup list`, `backup delete` (mit Einmalcode), `restore` (mit Einmalcode), `restart` (lokal oder über lokales Crafty-Ziel), `config reload`, `sources`, `exclude`, `include`, `match`, `pick`, `confirm`/`cancel`/`later` sowie Hauptmenü und Protokoll-GUI.
* **Offline eingeschränkt:** ausschließlich internetabhängige Funktionen (`check`, `download`, Abfrage externer Updatequellen, Online-Prüfung von Paper-Builds).
* **Automatische Erholung:** Ein temporärer Verbindungsausfall beschädigt den Plugin-Zustand nicht dauerhaft. Sobald beim periodischen Takt, beim manuellen `check`/`download` oder bei `/scamupdates config reload` wieder Internet erreichbar ist, wechselt ScamUpdates selbstständig zurück nach `ACTIVE`.

## Menü (GUI)

`/scamupdates` oder `/update` ohne Argumente öffnet das Hauptmenü (nur für Spieler; die Konsole bekommt die Hilfe). Aufbau: Statusleiste (Minecraft-Version mit Kompatibilitätsstatus, Paper-Build und neuester stabiler Build, Pluginanzahl, Updates, Serversteuerung mit Klick „Verbindung prüfen“, Neustart-/Warteschlangen-Status mit aktivem Countdown und nächstem Zeitplan), darunter die 6 Funktionsbereiche:

* **Update-Check:** Schnellprüfung oder detaillierte Prüfung mit Fortschrittsbalken, Abbruch per Barrier-Button und filterbarer Ergebnisliste.
* **Einzelplugin-Verwaltung (`Plugin-Bibliothek` & `Detailansicht`):** Alle installierten Plugins mit Version, letzter bekannter Version, Quelle, Status, Kompatibilität und Backup-Info; Seitenwechsel, Amboss-Suche, Statusfilter, Sortierung; pro Plugin Einzelprüfung, Einzeldownload (`downloadSingle`), Kandidatenauswahl (inkl. **Keins davon**), Warteschlangen-Steuerung, Ausschluss/Wiederaufnahme und Backup-Wiederherstellung.
* **Update-Warteschlange:** Dauerhaft vorgemerkte Plugins (`queue.yml`), automatische Prüfung beim Start und bei Update-Checks, Admin-Aktionen **Jetzt aktualisieren**, **Später** (`queue.snooze-minutes`) und **Nicht mehr erinnern**, Mehrdeutigkeits-Auswahl mit **Keins davon**.
* **Geplante Neustarts & Editor:** Wiederkehrende Pläne (`HOURLY`, `DAILY`, `WEEKLY`, `MULTI_DAY`) in `Europe/Berlin` (DST-sicher), Wochentags-Auswahl, Konflikt- und Duplikatprüfung, Aktivieren/Deaktivieren/Löschen.
* **Einmaliger Neustart:** 7 Presets (**Sofort** ohne Warnphase, 10s, 1m, 5m, 10m, 30m, 1h), benutzerdefinierte Eingabe (Stunden/Minuten/Sekunden + Grund) per Amboss mit Live-Vorschau von Dauer und Zielzeit, Abbruch-Button und Bereitschaftsanzeige. Ein neuer Countdown ersetzt immer einen vorherigen; nach einem Neustart wird ein noch offener einmaliger Countdown fortgesetzt, abgelaufene werden verworfen.
* **Protokoll:** Alle Updates, Rollbacks, Backup-Löschungen, Warteschlangen-Aktionen, Zeitpläne und Neustarts, sortierbar (neueste, älteste, Probleme zuerst).
* Alle Klicks werden serverseitig auf Pluginzustand, Berechtigung und `click-cooldown-ms` geprüft. Items sind nicht entnehmbar; Drag, Shift-Klick und Zahlentasten sind gesperrt.
* **Items und Köpfe:** Standardmäßig werden Materialien verwendet (Farbstoffe als Statusfarben). Custom-Köpfe sind pro Item über `texture` in der `gui.yml` möglich (Base64 oder `textures.minecraft.net`-URL; andere Hosts werden abgelehnt). Es werden **keine erfundenen Texturwerte** mitgeliefert. Plugin-Icons als Köpfe sind technisch nicht möglich.
* Die Optik wurde nicht gesehen; Anpassungen laufen über `gui.yml` (Titel, Materialien, Namen, Lores, Sounds, Design).

## Einmalcode-Bestätigung

Kritische Aktionen (Downloads/Updates inkl. Einzelupdates und Warteschlangen-Downloads, Wiederherstellung, Backup-Löschung, direkter `/scamupdates restart`-Befehl, Ausschließen/Aufnehmen) brauchen einen zufälligen Code, der an **Spieler und Aktion** gebunden, **einmalig** und zeitlich begrenzt ist (`confirmations.*`). Nicht erforderlich: Update-Check sowie Anlegen, Bearbeiten, Abbrechen oder Sofort-/Countdown-Auslösen von **geplanten und einmaligen Neustarts**.

* `confirmations.method: anvil`: Es öffnet sich ein Amboss-Fenster, der Code steht im Chat und im Papier-Tooltip. Code in das Textfeld tippen, dann auf das **Papier** (linkes Feld oder Ergebnisfeld) klicken. Das Klicken statt „Ergebnis entnehmen“ ist Absicht: sonst bräuchten Spieler im Überlebensmodus Erfahrungslevel. Fenster schließen = abbrechen. Nach `max-attempts` falschen Eingaben wird die Aktion abgebrochen.
* Konsole oder `method: chat`: klickbarer Chat-Token (`/scamupdates confirm <Code>`).
* Fällt das Amboss-Fenster aus (`openAnvil` nicht verfügbar) oder ist `gui.yml: input-method: chat`, wird per Chat eingegeben (Eingabe oder `abbrechen`).
* Der Amboss-Weg nutzt Paper-API-Aufrufe (`HumanEntity#openAnvil`, `PrepareAnvilEvent`), die ich nicht gegen 1.20–1.21.11 verifiziert habe; ob Bedrock-Clients (Geyser) das Fenster bedienen können, ist ungeklärt.
* Abschalten nur über die Config (`confirmations.enabled: false` oder je Aktion).

## Commands

Alle Befehle beginnen mit `/scamupdates` (Alias `/scamu`) oder `/update`; beide verhalten sich identisch.

| Command | Berechtigung | Beschreibung |
|---|---|---|
| (ohne Argument) | `scamupdates.check` | Hauptmenü (Spieler) bzw. Hilfe (Konsole) |
| `help`, `status`, `list [updates\|all] [Seite]`, `match [Plugin]` | `scamupdates.check` | Hilfe, Status, Listen, offene Zuordnungen |
| `check` | `scamupdates.check` | Updates suchen (ohne Bestätigung, mit Wartezeit gegen API-Limits) |
| `download` | `scamupdates.download` | Gefundene Updates laden und vormerken (Einmalcode) |
| `restore <Plugin\|all>` | `scamupdates.restore` | Rollback (Einmalcode) |
| `backup list [Seite]` / `backup delete <Plugin\|all>` | `scamupdates.backup` / `scamupdates.backup.delete` | Backups anzeigen / löschen (Einmalcode) |
| `restart` | `scamupdates.restart` | Neustart über Crafty oder `/restart`, mit Vorprüfung |
| `single [check\|update <Plugin>]` | `scamupdates.update.single` | Plugin-Bibliothek öffnen oder einzelnes Plugin prüfen/updaten (Einmalcode bei Download) |
| `queue [list\|add\|remove\|later\|dismiss\|update\|check\|download]` | `scamupdates.queue` | Update-Warteschlange öffnen, verwalten, zurückstellen, entfernen oder laden |
| `schedule [list\|add\|toggle\|remove\|onetime\|cancel]` | `scamupdates.schedule` | Wiederkehrende Pläne & einmalige Neustarts verwalten (ohne Einmalcode) |
| `history` | `scamupdates.history` | Protokoll (Menü bzw. letzte 10 Einträge im Chat) |
| `config reload` | `scamupdates.config` | Config und `gui.yml` neu laden, Startprüfungen wiederholen |
| `sources`, `exclude`/`include <Plugin>`, `pick <Plugin> <Nr>` | `scamupdates.sources` | Quellen, Ausschlüsse, Zuordnung |
| `confirm <Code>`, `cancel [Code]`, `later` | – (code-gebunden) | Bestätigen, abbrechen, später |

Berechtigungen im Detail: `scamupdates.check`, `scamupdates.download`, `scamupdates.update.plugins`, `scamupdates.update.single`, `scamupdates.queue`, `scamupdates.schedule`, `scamupdates.restore`, `scamupdates.backup`, `scamupdates.backup.delete`, `scamupdates.restart`, `scamupdates.history`, `scamupdates.config`, `scamupdates.sources`, `scamupdates.update.server` (reserviert für Schritt 4). `scamupdates.admin` (Standard: OP) enthält alle; `scamupdates.notify` steuert Join- und Warteschlangen-Benachrichtigungen sowie Status-/Warnmeldungen. Befehle und Menü verwenden dieselben Prüfungen; die Berechtigung wird beim Klick und bei der Einlösung des Codes erneut geprüft. Nur Spieler und die Konsole dürfen Befehle ausführen.

**Alias-Kollision `/update`:** Belegt ein anderes Plugin `/update`, behält dieses den Namen; ScamUpdates warnt im Log, der Alias ist dann als `/scamupdates:update` erreichbar.

## Konfiguration

* `config.yml` (Verhalten, Sicherheit, Quellen, Nachrichten), `gui.yml` (Oberfläche). Kurze Kommentare; ausführliche Erklärungen stehen in dieser README und in `docs/`.
* **Migration:** fehlende Schlüssel werden ergänzt, vorhandene Werte nie verändert. Bei einem Versionswechsel werden die **Kommentare** bestehender Schlüssel durch die neuen, kompakten ersetzt (eigene Kommentare gehen dabei verloren; die Sicherung `config.yml.bak-v<alt>` enthält sie). Eine defekte `gui.yml` führt nicht zum Fehlerzustand, sondern zu Standardwerten plus Logeintrag.
* Schlüssel `yes`/`no` müssen in YAML in Anführungszeichen stehen (sonst Boolean); die `gui.yml` tut das bereits.

## Update-Protokoll und Vorgänge

* `history.jsonl`: Zeit, Aktion, Ergebnis (`OK`/`WARN`/`FAILED`/`INFO`), Auslöser, Minecraft-Ziel, Meldung, Plugin-Details (alt → neu, Status). Geheimnisse (Crafty-Token) werden vor dem Schreiben geschwärzt. Aufbewahrung: `history.retention-days`, `history.max-entries`; Bereinigung beim Start und täglich.
* `operations.yml`: jeder Vorgang hat eine ID und einen Status. Gleicher Typ + Schlüssel kann nicht doppelt laufen. Nach einem Serverneustart werden laufende Vorgänge als `INTERRUPTED` markiert und **nie** fortgesetzt oder doppelt ausgeführt.

## Sicherheit

* **Downloads:** nur HTTPS (`downloads.allow-http: false`), Größen- und Zeitlimit, Temp-Datei außerhalb von `plugins/update`, danach Prüfsummen (SHA-1/256/512 der Quelle; manuell `sha256`) und JAR-Validierung (Magic Bytes, Zip-Slip, `plugin.yml`/`paper-plugin.yml`, Hauptklasse, **Pluginname muss passen**, `api-version` nicht neuer als der Server). Erst danach wird verschoben. Dateinamen werden gegen Path Traversal geprüft; Dateien werden nie ausgeführt oder entpackt. Schlägt ein Schritt fehl, bleiben installierte JAR **und** Backup unverändert.
* **Crafty-Token:** nur im `Authorization`-Header, nie in Logs, Protokoll, `toString()` oder Chat; Crafty-Anfragen folgen keinen Weiterleitungen; SSL-Prüfung standardmäßig an.
* Kein `/reload`, keine Hot-Reloads, keine Konsolenfilter, keine Änderung fremder Plugin-Configs.
* Netzwerk, Downloads und Dateizugriffe laufen nicht auf dem Hauptthread; Bukkit-Aufrufe nur auf dem Hauptthread.

## Update-Vorgang

Bestätigter Download → parallel laden und validieren (`downloads.max-parallel`) → pro Plugin: Backup-Kopie vorbereiten und prüfen → validierte Datei nach `plugins/update/<Dateiname der installierten JAR>` verschieben → Backup ersetzen → Eintrag in `transactions.yml`. Paper/Bukkit übernimmt Dateien aus dem Update-Ordner nur bei **gleichem Dateinamen**; ScamUpdates übernimmt ihn von der installierten Datei. Nach dem Neustart vergleicht eine Prüfung den SHA-256 der installierten JAR mit der vorgemerkten Datei (`APPLIED`/`NOT_APPLIED`/`MISMATCH`, in Log, Protokoll und `status`).

## Backup und Rollback

* `plugins/update-backup/`: pro Plugin **höchstens eine** alte JAR (Originaldateiname), Zuordnung lesbar in `scamupdates-backups.yml`. Config-Ordner werden nie angefasst; gelöscht werden nur verzeichnete Backup-JARs.
* `restore <Plugin>`: validiert die Backup-JAR und legt sie als Update ab; die aktuelle JAR wird zum **neuen Backup** (Tausch, also umkehrbar). `restore all` fragt ebenfalls nach. Ein Plugin mit vorgemerkter Änderung wird gesperrt, bis der Neustart die Änderung übernommen hat.
* **Manuell, falls ScamUpdates nicht lädt:** Server stoppen, JAR aus `plugins/update-backup/` zurück nach `plugins/` kopieren (gleicher Name, ersetzen), starten.

## Updatequellen und Grenzen

Zuordnung: manuelle Config, gespeicherte Zuordnung/Ausschluss, Links in Website/Beschreibung der `plugin.yml`, Namenssuche (Modrinth, Hangar, Spiget; exakter normalisierter Name). **Mehrere Treffer werden nie automatisch gewählt**; Auswahl über `match`/`pick`, inklusive „Keins davon“ (dauerhaft gespeichert, nur eine manuelle Zuordnung in der Config überschreibt das).

* **Modrinth, Hangar:** offizielle APIs (Hangar nur PAPER). **SpigotMC:** über die Community-API Spiget; kostenpflichtige/externe Ressourcen ohne Direktdownload. **GitHub:** Releases (genau ein passendes `.jar`-Asset), Suche nur mit Token. **Jenkins** und **direkter Link:** nur manuell (`direct` braucht `sha256` oder `version`). **GitHub Actions: nicht implementiert** (Artefakt-Downloads verlangen Authentifizierung).
* Es kann nicht jedes Plugin erkannt werden; unbekannte Versionsformate können falsche „kein Update“-Ergebnisse liefern; Abhängigkeiten anderer Plugins werden nicht geprüft.
* **Fremde Update-Meldungen (Abschnitt 4 der Ursprungsvorgabe) werden nicht unterdrückt:** es gibt keine einheitliche Schnittstelle, und jede Integration bräuchte die echten Config-Schlüssel des jeweiligen Plugins.

## Dateien in `plugins/ScamUpdates/`

`config.yml`, `gui.yml`, `matches.yml`, `transactions.yml`, `operations.yml`, `queue.yml`, `restarts.yml`, `history.jsonl`, `notified.yml`, `logs/scamupdates.log`, `tmp/` (wird beim Start geleert).

## Tests

JUnit 5 (`./gradlew test`): Versionsvergleich und Minecraft-Matching, JAR-Validierung, Download-Pipeline (manipulierte/falsche JARs lassen installierte JAR und Backup unberührt), Einzelplugin-Update (`checkSingle`, `downloadSingle`, Fehlerschutz, `OperationLock`), Update-Warteschlange (`UpdateQueueStore`, `QueueService`, Persistenz, Duplikatschutz, Später/Nicht mehr erinnern, Mehrdeutigkeit/Keins davon), Backup/Staging, Rollback (auch im Offline-Betrieb), Bestätigungen (Spielerbindung, Ablauf, Einmaligkeit, Codelänge), Permissions und Online-/Offline-Befehlstrennung (`CommandPolicy`), Internetprüfung und Offline-Wiederherstellung (`ConnectivityGuard`), Crafty (Mock-Server) & Crafty-Isolation (`CraftyCoordinator`), Config-Migration, Plugin-Index, Zuordnung, Neustart-Vorprüfung, Zeitpläne & DST (`RestartScheduleStore` in `Europe/Berlin`, Konflikte, Presets, benutzerdefinierte H/M/S-Zeit, Warnzeiten & Wiederaufnahme in `RestartCoordinator`), Kompatibilitätsstatus, Protokoll, Vorgangsverwaltung, Paper-API-Parsing, lokaler Neustart, GUI-Filter/-Sortierung/-Parser und GUI-/Config-Ressourcen. Nicht testbar ohne echten Paper-Server: Live-Menü-Rendering, Amboss-Eingabe/Fenster, Optik und Live-Neustart.
