package de.scamupdates.queue;

import de.scamupdates.logging.ScamLog;
import de.scamupdates.model.UpdateCandidate;
import de.scamupdates.update.UpdateService;
import de.scamupdates.utils.AtomicFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Strukturelle Grundlage fuer die kommende Update-Warteschlange:
 * haelt vorgemerkte Plugins persistent in queue.yml und loest sie gegen einen
 * UpdateService.Snapshot zu konkreten Update-Kandidaten auf.
 */
public final class UpdateQueueStore {

    public record Entry(String plugin, String targetVersion, String sourceId, String addedBy, long addedAt) {}

    private final Path file;
    private final ScamLog log;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean loaded;

    public UpdateQueueStore(Path file, ScamLog log) {
        this.file = file;
        this.log = log;
    }

    public synchronized void enqueue(UpdateCandidate candidate, String actor) {
        if (candidate == null) return;
        enqueue(candidate.plugin().name(), candidate.toVersion(), candidate.project().sourceId(), actor);
    }

    public synchronized void enqueue(String plugin, String targetVersion, String sourceId, String actor) {
        load();
        if (plugin == null || plugin.isBlank()) return;
        String key = key(plugin);
        entries.put(key, new Entry(plugin.trim(), safe(targetVersion), safe(sourceId),
                actor == null || actor.isBlank() ? "system" : actor.trim(), System.currentTimeMillis()));
        save();
    }

    public synchronized boolean remove(String plugin) {
        load();
        if (plugin == null || plugin.isBlank()) return false;
        boolean removed = entries.remove(key(plugin)) != null;
        if (removed) save();
        return removed;
    }

    public synchronized int clear() {
        load();
        int n = entries.size();
        if (n > 0) {
            entries.clear();
            save();
        }
        return n;
    }

    public synchronized boolean contains(String plugin) {
        load();
        return plugin != null && entries.containsKey(key(plugin));
    }

    public synchronized Optional<Entry> get(String plugin) {
        load();
        return plugin == null ? Optional.empty() : Optional.ofNullable(entries.get(key(plugin)));
    }

    public synchronized List<Entry> all() {
        load();
        return List.copyOf(entries.values());
    }

    /** Ordnet alle Warteschlangen-Eintraege den aktuell verfuegbaren Update-Kandidaten zu. */
    public synchronized List<UpdateCandidate> resolveCandidates(UpdateService.Snapshot snapshot) {
        load();
        if (snapshot == null || entries.isEmpty()) return List.of();
        Map<String, UpdateCandidate> byName = new LinkedHashMap<>();
        for (UpdateCandidate c : snapshot.updates()) {
            byName.put(key(c.plugin().name()), c);
        }
        List<UpdateCandidate> out = new ArrayList<>();
        for (String k : entries.keySet()) {
            UpdateCandidate c = byName.get(k);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** Entfernt Plugins aus der Warteschlange (z. B. nach erfolgreichem Download oder Rollback). */
    public synchronized int removeAll(Collection<String> pluginNames) {
        load();
        if (pluginNames == null || pluginNames.isEmpty()) return 0;
        int n = 0;
        for (String p : pluginNames) {
            if (p != null && entries.remove(key(p)) != null) n++;
        }
        if (n > 0) save();
        return n;
    }

    private static String key(String plugin) { return plugin.trim().toLowerCase(Locale.ROOT); }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (file == null || !Files.isRegularFile(file)) return;
        YamlConfiguration y = yaml();
        try {
            y.loadFromString(Files.readString(file));
        } catch (IOException | InvalidConfigurationException e) {
            log.error("queue.yml nicht lesbar", e);
            return;
        }
        ConfigurationSection sec = y.getConfigurationSection("queue");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(k);
            if (s == null) continue;
            String plugin = s.getString("plugin", k);
            entries.put(key(plugin), new Entry(plugin, s.getString("target-version", ""),
                    s.getString("source", ""), s.getString("added-by", "system"), s.getLong("added-at", 0)));
        }
    }

    private void save() {
        if (file == null) return;
        YamlConfiguration y = yaml();
        for (Entry e : entries.values()) {
            String b = "queue/" + key(e.plugin()) + "/";
            y.set(b + "plugin", e.plugin());
            y.set(b + "target-version", e.targetVersion());
            y.set(b + "source", e.sourceId());
            y.set(b + "added-by", e.addedBy());
            y.set(b + "added-at", e.addedAt());
        }
        try {
            AtomicFiles.writeString(file, y.saveToString());
        } catch (IOException e) {
            log.error("queue.yml konnte nicht gespeichert werden", e);
        }
    }

    private static YamlConfiguration yaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        return y;
    }
}
