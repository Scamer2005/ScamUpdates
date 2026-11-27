package de.scamupdates.backup;

import de.scamupdates.logging.ScamLog;
import de.scamupdates.utils.AtomicFiles;
import de.scamupdates.utils.Hashing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * plugins/update-backup/: pro Plugin hoechstens eine alte JAR (Dateiname = Originaldateiname im plugins-Ordner),
 * Zuordnung in scamupdates-backups.yml (lesbar, fuer manuelle Wiederherstellung). Config-Ordner werden nie beruehrt.
 */
public final class BackupManager {

    public record Entry(String plugin, String fileName, String version, String sha256, long createdAt) {}

    public static final class Prepared {
        private final Entry entry;
        private final Path tmp;
        private Prepared(Entry entry, Path tmp) { this.entry = entry; this.tmp = tmp; }
        public Entry entry() { return entry; }
    }

    private static final String META = "scamupdates-backups.yml";

    private final Path backupDir;
    private final ScamLog log;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean loaded;

    public BackupManager(Path backupDir, ScamLog log) {
        this.backupDir = backupDir;
        this.log = log;
    }

    public Path directory() { return backupDir; }

    /** Kopiert die installierte JAR in eine temporaere Datei im Backup-Ordner und prueft die Kopie. */
    public synchronized Prepared prepare(String plugin, String version, Path installedJar) throws IOException {
        Files.createDirectories(backupDir);
        String fileName = installedJar.getFileName().toString();
        AtomicFiles.requireInside(backupDir, fileName);
        Path tmp = backupDir.resolve(fileName + ".part-" + UUID.randomUUID());
        try {
            String srcHash = Hashing.sha256(installedJar);
            Files.copy(installedJar, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (!srcHash.equals(Hashing.sha256(tmp))) throw new IOException("Backup-Kopie stimmt nicht mit dem Original ueberein");
            return new Prepared(new Entry(plugin, fileName, version, srcHash, System.currentTimeMillis()), tmp);
        } catch (IOException | RuntimeException e) {
            AtomicFiles.deleteQuietly(tmp);
            throw e;
        }
    }

    /** Ersetzt das bisherige Backup dieses Plugins atomar durch das vorbereitete. */
    public synchronized void commit(Prepared prepared) throws IOException {
        load();
        Entry e = prepared.entry;
        String key = e.plugin().toLowerCase(Locale.ROOT);
        Entry old = entries.get(key);
        AtomicFiles.moveReplace(prepared.tmp, AtomicFiles.requireInside(backupDir, e.fileName()));
        entries.put(key, e);
        try {
            saveMeta();
        } catch (IOException ex) {
            log.error("Backup-Metadaten konnten nicht gespeichert werden", ex);
            throw ex;
        }
        if (old != null && !old.fileName().equals(e.fileName())) {
            AtomicFiles.deleteQuietly(AtomicFiles.requireInside(backupDir, old.fileName()));
        }
        log.info("Backup erstellt: " + e.plugin() + " " + e.version() + " (" + e.fileName() + ")");
    }

    public void discard(Prepared prepared) {
        AtomicFiles.deleteQuietly(prepared.tmp);
    }

    public synchronized List<Entry> list() {
        load();
        List<Entry> out = new ArrayList<>(entries.values());
        out.sort(Comparator.comparing(x -> x.plugin().toLowerCase(Locale.ROOT)));
        return out;
    }

    public synchronized Optional<Entry> get(String plugin) {
        load();
        return Optional.ofNullable(entries.get(plugin.toLowerCase(Locale.ROOT)));
    }

    public Path fileOf(Entry e) throws IOException {
        return AtomicFiles.requireInside(backupDir, e.fileName());
    }

    /** Prueft, ob die Backup-Datei existiert und unveraendert ist. */
    public boolean verify(Entry e) {
        try {
            Path p = fileOf(e);
            return Files.isRegularFile(p) && Hashing.sha256(p).equals(e.sha256());
        } catch (IOException ex) {
            return false;
        }
    }

    /** Loescht ausschliesslich die in den Metadaten verzeichnete Backup-JAR dieses Plugins. */
    public synchronized Optional<Entry> delete(String plugin) throws IOException {
        load();
        Entry e = entries.get(plugin.toLowerCase(Locale.ROOT));
        if (e == null) return Optional.empty();
        Files.deleteIfExists(AtomicFiles.requireInside(backupDir, e.fileName()));
        entries.remove(plugin.toLowerCase(Locale.ROOT));
        saveMeta();
        log.info("Backup geloescht: " + e.plugin());
        return Optional.of(e);
    }

    public synchronized int deleteAll() throws IOException {
        load();
        int n = 0;
        for (Entry e : new ArrayList<>(entries.values())) {
            if (delete(e.plugin()).isPresent()) n++;
        }
        return n;
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        Path meta = backupDir.resolve(META);
        if (!Files.isRegularFile(meta)) return;
        YamlConfiguration y = yaml();
        try {
            y.loadFromString(Files.readString(meta));
        } catch (IOException | InvalidConfigurationException e) {
            log.error("Backup-Metadaten nicht lesbar: " + meta.getFileName(), e);
            return;
        }
        ConfigurationSection sec = y.getConfigurationSection("backups");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            Entry e = new Entry(s.getString("plugin", key), s.getString("file", ""), s.getString("version", "?"),
                    s.getString("sha256", ""), s.getLong("created", 0));
            try {
                if (Files.isRegularFile(AtomicFiles.requireInside(backupDir, e.fileName()))) entries.put(key, e);
                else log.warn("Backup-Datei fehlt, Eintrag verworfen: " + e.plugin());
            } catch (IOException ex) {
                log.warn("Unsicherer Backup-Eintrag verworfen: " + key);
            }
        }
    }

    private void saveMeta() throws IOException {
        YamlConfiguration y = yaml();
        for (Entry e : entries.values()) {
            String base = "backups/" + e.plugin().toLowerCase(Locale.ROOT) + "/";
            y.set(base + "plugin", e.plugin());
            y.set(base + "file", e.fileName());
            y.set(base + "version", e.version());
            y.set(base + "sha256", e.sha256());
            y.set(base + "created", e.createdAt());
        }
        AtomicFiles.writeString(backupDir.resolve(META), "# ScamUpdates Backup-Zuordnung. Zur manuellen Wiederherstellung die JAR\n"
                + "# (Dateiname = Originalname) aus diesem Ordner zurueck nach plugins/ kopieren.\n" + y.saveToString());
    }

    private static YamlConfiguration yaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        return y;
    }
}
