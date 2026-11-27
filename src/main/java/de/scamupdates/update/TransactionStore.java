package de.scamupdates.update;

import de.scamupdates.logging.ScamLog;
import de.scamupdates.utils.AtomicFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Vorgemerkte (gestagte) Aenderungen, die erst nach einem Serverneustart wirksam werden. Ueberlebt Neustarts. */
public final class TransactionStore {

    public enum Kind { UPDATE, RESTORE }

    public record Pending(String plugin, Kind kind, String fromVersion, String toVersion,
                          String stagedFile, String stagedSha256, long time) {}

    private final Path file;
    private final ScamLog log;
    private final Map<String, Pending> map = new LinkedHashMap<>();
    private boolean loaded;

    public TransactionStore(Path file, ScamLog log) {
        this.file = file;
        this.log = log;
    }

    public synchronized void put(Pending p) throws IOException {
        load();
        map.put(p.plugin().toLowerCase(Locale.ROOT), p);
        save();
    }

    public synchronized void remove(String plugin) throws IOException {
        load();
        if (map.remove(plugin.toLowerCase(Locale.ROOT)) != null) save();
    }

    public synchronized Optional<Pending> get(String plugin) {
        load();
        return Optional.ofNullable(map.get(plugin.toLowerCase(Locale.ROOT)));
    }

    public synchronized List<Pending> all() {
        load();
        return new ArrayList<>(map.values());
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(file)) return;
        YamlConfiguration y = yaml();
        try {
            y.loadFromString(Files.readString(file));
        } catch (IOException | InvalidConfigurationException e) {
            log.error("transactions.yml nicht lesbar", e);
            return;
        }
        ConfigurationSection sec = y.getConfigurationSection("pending");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            Kind kind;
            try {
                kind = Kind.valueOf(s.getString("kind", "UPDATE"));
            } catch (IllegalArgumentException e) {
                kind = Kind.UPDATE;
            }
            map.put(key, new Pending(s.getString("plugin", key), kind, s.getString("from", "?"), s.getString("to", "?"),
                    s.getString("file", ""), s.getString("sha256", ""), s.getLong("time", 0)));
        }
    }

    private void save() throws IOException {
        YamlConfiguration y = yaml();
        for (Pending p : map.values()) {
            String base = "pending/" + p.plugin().toLowerCase(Locale.ROOT) + "/";
            y.set(base + "plugin", p.plugin());
            y.set(base + "kind", p.kind().name());
            y.set(base + "from", p.fromVersion());
            y.set(base + "to", p.toVersion());
            y.set(base + "file", p.stagedFile());
            y.set(base + "sha256", p.stagedSha256());
            y.set(base + "time", p.time());
        }
        AtomicFiles.writeString(file, y.saveToString());
    }

    private static YamlConfiguration yaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        return y;
    }
}
