# ScamUpdates

Update-Manager für Paper **1.20 bis 1.21.x**: erkennt installierte Plugins, sucht Updates (Modrinth, Hangar, SpigotMC/Spiget, GitHub Releases, Jenkins, direkte Links), lädt sie geprüft nach `plugins/update/`, sichert die alte JAR in `plugins/update-backup/`, bietet Rollback, ein Menü (GUI) und löst Neustarts über **Crafty Controller** oder lokal per `/restart` aus.

> ## Status (bitte lesen)
> Die **Basis** (Update-Suche, Download, Backup, Rollback, Crafty, Befehle) wurde vom Betreiber gebaut und getestet. Alles in der Tabelle „Neu in diesem Stand“ wurde **ohne Compiler geschrieben und weder gebaut noch ausgeführt**. Rechne mit einzelnen Compilerfehlern, fehlschlagenden Tests und Optikproblemen im Menü; Fehlermeldungen bitte zurückschicken. Geprüft wurde nur per Skript: Klammerbalance, Paketnamen, YAML-Syntax, dass jeder im Code genutzte Nachrichten-/GUI-Schlüssel existiert, und dass keine YAML-Schlüssel als Boolean gelesen werden.

## Neu in diesem Stand

| Bereich | Stand |
|---|---|
| Versionsbereich 1.20–1.21.x mit Kompatibilitätsstatus (`getestet`/`ungetestet`/`zu alt`/`zu neu`) | geschrieben, ungetestet |
| Crafty **optional** (`crafty.enabled: false` → lokaler `/restart`, kein Fehlerzustand) | geschrieben, ungetestet |
| Kompakte `config.yml`, `gui.yml`, Migration (inkl. Kommentar-Aktualisierung) | geschrieben, ungetestet |
| Vorgangsverwaltung (IDs, Status, Doppelstart-Schutz, neustartsicher) | geschrieben, ungetestet |
| Update-Protokoll (`history.jsonl`, Aufbewahrung, Token-Schwärzung) | geschrieben, ungetestet |
| Paper-Prüfung über den PaperMC-Downloaddienst (Fill v3) für Status und Update-Check | geschrieben, ungetestet |
| GUI-Grundsystem, Hauptmenü mit Statusanzeige, Update-Check-Menü (Fortschritt, Animation, Abbrechen, Zusammenfassung), Ergebnisliste, Protokoll-Menü | geschrieben, ungetestet |
| Einmalcode per Amboss-Fenster (Chat-Fallback) für alle Bestätigungen | geschrieben, ungetestet |
| `/update` = `/scamupdates`, neue Permissions, `history` | geschrieben, ungetestet |

**Noch nicht umgesetzt** (kommen in den nächsten Abschnitten, dafür gibt es bewusst weder Menüeinträge noch Befehle): Gesamtes Serverupdate (Paper + Plugins) samt Austausch-Helfer, „Alle Plugins updaten“-Ablauf im Menü, Plugin-Bibliothek („Einzelne Plugins“), Update-Warteschlange (Erinnerungen), geplante und einmalige Neustarts, Backup-Menü, Einstellungsmenü. Diese Funktionen sind über die bestehenden Befehle (`download`, `restore`, `backup`, `restart`, …) weiter erreichbar.

## Bauen

Gradle-Projekt in IntelliJ öffnen, JDK 21 oder neuer, Task `build` → `build/libs/ScamUpdates.jar` (Kopie in `dist/`). Bytecode-Ziel Java 21; Abhängigkeit nur `paper-api:1.21.11-R0.1-SNAPSHOT` (`compileOnly`). `gradlew` fehlt (kein Binärfile): `gradle wrapper` erzeugt es.

## Version, Java, Linux

* **Unterstützt:** Minecraft 1.20 bis 1.21.x. Neuere Versionen (1.22, 26.x) und ältere als 1.20 führen **nicht** zum Absturz; ScamUpdates läuft weiter, zeigt aber den Status „nicht unterstützt“ (Log, `/scamupdates status`, Menü).
* **„Getestet“** wird nur angezeigt, wenn du die Version unter `compat.tested-versions` einträgst. Der Code behauptet von sich aus keine getestete Unterstützung. Verifiziert ist bisher nur der Build gegen die API 1.21.11 und der Betrieb auf deinem Server.
* **Versionsabhängiges** ist isoliert: Plugin-Metadaten (`PluginMeta` oder `PluginDescriptionFile`, nur eine Variante wird geladen), Item-Flags (per `valueOf`), Amboss-Text (Reflection auf `AnvilView`/`AnvilInventory`). Gemeinsamer Code greift nie auf `InventoryView` zu (Klasse/Interface-Wechsel in 1.21), sondern vergleicht nur Inventar-Instanzen. `plugin.yml` deklariert `api-version: '1.20'`.
* Zielserver mit Java 25: der Bytecode (Java 21) läuft darauf; ob sehr alte Paper-Builds (1.20.x) unter Java 25 starten, ist **nicht geprüft**.
* Linux: nichts Besonderes nötig. Für den lokalen Neustart muss das `restart-script` aus der `spigot.yml` existieren.

## Crafty Controller optional

* `crafty.enabled: true` (Standard): alle Crafty-Angaben müssen eingetragen sein, sonst Fehlerzustand `DISABLED_MISSING_CONFIG`. Verbindung und Token werden beim Start geprüft; Neustarts laufen über die Crafty-API. Einrichtung: [`docs/CRAFTY.md`](docs/CRAFTY.md).
* `crafty.enabled: false`: **kein** Fehlerzustand, keine Crafty-Verbindung, alle anderen Funktionen bleiben verfügbar. Neustarts (nach Updates und manuell) laufen über den Minecraft-Befehl `/restart` (`local-restart.command`).
* **Wichtig zu `/restart`:** Der Befehl startet nur neu, wenn in der `spigot.yml` unter `settings.restart-script` ein **vorhandenes Skript** steht; sonst fährt er den Server nur herunter. ScamUpdates prüft das beim Start und vor jedem Neustart. Fehlt der Mechanismus: Fehlermeldung in Konsole und an Admins (`scamupdates.notify`), und Updates gelten dann nur als **vorbereitet** (Warnung nach dem Download, Protokolleintrag `WARN`). Ein Neustart wird nie vorgetäuscht.
* `/restart` lädt die Paper-JAR beim neuen Start nur neu, ersetzt sie aber nicht. Für Paper-Versionswechsel braucht es einen Austausch-Mechanismus (Teil des noch offenen Serverupdate-Abschnitts).
* Internetprüfung und alle Sicherheitsprüfungen sind unabhängig von Crafty.

## Menü (GUI)

`/scamupdates` oder `/update` ohne Argumente öffnet das Hauptmenü (nur für Spieler; die Konsole bekommt die Hilfe). Aufbau: Statusleiste (Minecraft-Version mit Kompatibilitätsstatus, Paper-Build und neuester stabiler Build, Pluginanzahl, Updates, Serversteuerung mit Klick „Verbindung prüfen“, ScamUpdates-Status), darunter die Funktionsbereiche. Die Statusanzeige aktualisiert sich selbstständig (`general.refresh-ticks` in der `gui.yml`).

* **Update-Check:** Schnellprüfung oder detaillierte Prüfung. Während der Prüfung: Fortschrittsbalken mit Animation, aktuelles Plugin, Dauer und Vorgangs-ID; unten rechts ein **Barrier** zum Abbrechen (Ja/Nein-Abfrage). Das Menü zu schließen bricht nichts ab, beim erneuten Öffnen wird der laufende Stand gezeigt. Danach Zusammenfassung (Updates, aktuell, Zuordnung offen, Probleme, ausgeschlossen, Paper-Status) und Ergebnisliste mit Filter, Detailgrad und Seiten.
* **Protokoll:** alle Updates, Rollbacks, Backup-Löschungen, Neustarts und Prüfungen, sortierbar (neueste, älteste, Probleme zuerst).
* Alle Klicks werden serverseitig neu geprüft (Plugin aktiv? Berechtigung? Doppelklick-Sperre `click-cooldown-ms`). Items sind nicht entnehmbar; Drag, Shift-Klick und Zahlentasten sind gesperrt.
* **Items und Köpfe:** Standardmäßig werden Materialien verwendet (Farbstoffe als Statusfarben). Custom-Köpfe sind pro Item über `texture` in der `gui.yml` möglich (Base64 oder `textures.minecraft.net`-URL; andere Hosts werden abgelehnt). Es werden **keine erfundenen Texturwerte** mitgeliefert. Plugin-Icons als Köpfe sind technisch nicht möglich.
* Die Optik wurde nicht gesehen; Anpassungen laufen über `gui.yml` (Titel, Materialien, Namen, Lores, Sounds, Design).

## Einmalcode-Bestätigung

Kritische Aktionen (Downloads/Updates, Wiederherstellung, Backup-Löschung, Neustart per Befehl, Ausschließen/Aufnehmen) brauchen einen zufälligen Code, der an **Spieler und Aktion** gebunden, **einmalig** und zeitlich begrenzt ist (`confirmations.*`). Nicht erforderlich: Update-Check, (später) Neustart-Planung.

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
| `history` | `scamupdates.history` | Protokoll (Menü bzw. letzte 10 Einträge im Chat) |
| `config reload` | `scamupdates.config` | Config und `gui.yml` neu laden, Startprüfungen wiederholen |
| `sources`, `exclude`/`include <Plugin>`, `pick <Plugin> <Nr>` | `scamupdates.sources` | Quellen, Ausschlüsse, Zuordnung |
| `confirm <Code>`, `cancel [Code]`, `later` | – (code-gebunden) | Bestätigen, abbrechen, später |

Weitere Permissions (für die kommenden Funktionen bereits angelegt): `scamupdates.update.plugins`, `scamupdates.update.server`, `scamupdates.update.single`, `scamupdates.queue`, `scamupdates.schedule`. `scamupdates.admin` (Standard: OP) enthält alle, `scamupdates.notify` steuert Join-Nachrichten (Updates, Fehlerzustand, fehlender Neustart-Mechanismus). Befehle und Menü verwenden dieselben Prüfungen; die Berechtigung wird beim Klick und bei der Einlösung des Codes erneut geprüft. Nur Spieler und die Konsole dürfen Befehle ausführen.

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

`config.yml`, `gui.yml`, `matches.yml`, `transactions.yml`, `operations.yml`, `history.jsonl`, `notified.yml`, `logs/scamupdates.log`, `tmp/` (wird beim Start geleert).

## Tests

JUnit 5 (`gradle test`): Versionsvergleich und Minecraft-Matching, JAR-Validierung, Download-Pipeline (manipulierte/falsche JARs lassen installierte JAR und Backup unberührt), Backup/Staging, Rollback, Bestätigungen (Spielerbindung, Ablauf, Einmaligkeit, Codelänge), Permissions, Internetprüfung, Crafty (Mock-Server), Config-Migration, Plugin-Index, Zuordnung, Neustart-Vorprüfung, Kompatibilitätsstatus, Protokoll, Vorgangsverwaltung, Paper-API-Parsing, lokaler Neustart, GUI-/Config-Ressourcen. **Noch nicht gelaufen** (siehe Status). Nicht testbar ohne Server: Menü-Rendering, Amboss-Fenster, Optik.
