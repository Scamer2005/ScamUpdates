package de.scamupdates.match;

import de.scamupdates.logging.ScamLog;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.utils.AtomicFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Persistente Zuordnungen und Ausschluesse (matches.yml, getrennt von der config.yml). */
public final class MatchStore {

    public enum Reason { NONE_OF_THESE, MANUAL_COMMAND }

    public record Exclusion(String plugin, Reason reason, long time) {}

    private final Path file;
    private final ScamLog log;
    private final Map<String, ProjectRef> assignments = new HashMap<>();
    private final Map<String, Exclusion> exclusions = new HashMap<>();
    private final Map<String, Long> unmatched = new HashMap<>();
    private boolean loaded;

    public MatchStore(Path file, ScamLog log) {
        this.file = file;
        this.log = log;
    }

    private static String key(String plugin) { return plugin.toLowerCase(Locale.ROOT); }

    public synchronized Optional<ProjectRef> assignment(String plugin) {
        load();
        return Optional.ofNullable(assignments.get(key(plugin)));
    }

    public synchronized Optional<Exclusion> exclusion(String plugin) {
        load();
        return Optional.ofNullable(exclusions.get(key(plugin)));
    }

    public synchronized void assign(String plugin, ProjectRef ref) throws IOException {
        load();
        assignments.put(key(plugin), ref);
        exclusions.remove(key(plugin));
        unmatched.remove(key(plugin));
        save();
    }

    public synchronized void exclude(String plugin, Reason reason) throws IOException {
        load();
        exclusions.put(key(plugin), new Exclusion(plugin, reason, System.currentTimeMillis()));
        assignments.remove(key(plugin));
        save();
    }

    public synchronized void removeExclusion(String plugin) throws IOException {
        load();
        if (exclusions.remove(key(plugin)) != null) save();
    }

    public synchronized void markUnmatched(String plugin) throws IOException {
        load();
        unmatched.put(key(plugin), System.currentTimeMillis());
        save();
    }

    /** false, wenn die letzte erfolglose Suche weniger als retryHours zurueckliegt. */
    public synchronized boolean shouldSearch(String plugin, int retryHours) {
        load();
        Long t = unmatched.get(key(plugin));
        return t == null || System.currentTimeMillis() - t >= retryHours * 3_600_000L;
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(file)) return;
        YamlConfiguration y = yaml();
        try {
            y.loadFromString(Files.readString(file));
        } catch (IOException | InvalidConfigurationException e) {
            log.error("matches.yml nicht lesbar", e);
            return;
        }
        ConfigurationSection a = y.getConfigurationSection("assignments");
        if (a != null) for (String k : a.getKeys(false)) {
            ConfigurationSection s = a.getConfigurationSection(k);
            if (s == null) continue;
            assignments.put(k, new ProjectRef(s.getString("source", ""), s.getString("project-id", ""),
                    s.getString("name", k), s.getString("url", "")));
        }
        ConfigurationSection x = y.getConfigurationSection("exclusions");
        if (x != null) for (String k : x.getKeys(false)) {
            ConfigurationSection s = x.getConfigurationSection(k);
            if (s == null) continue;
            Reason r;
            try {
                r = Reason.valueOf(s.getString("reason", "NONE_OF_THESE"));
            } catch (IllegalArgumentException e) {
                r = Reason.NONE_OF_THESE;
            }
            exclusions.put(k, new Exclusion(s.getString("plugin", k), r, s.getLong("time", 0)));
        }
        ConfigurationSection u = y.getConfigurationSection("unmatched");
        if (u != null) for (String k : u.getKeys(false)) unmatched.put(k, u.getLong(k));
    }

    private void save() throws IOException {
        YamlConfiguration y = yaml();
        assignments.forEach((k, r) -> {
            y.set("assignments/" + k + "/source", r.sourceId());
            y.set("assignments/" + k + "/project-id", r.projectId());
            y.set("assignments/" + k + "/name", r.name());
            y.set("assignments/" + k + "/url", r.url());
        });
        exclusions.forEach((k, e) -> {
            y.set("exclusions/" + k + "/plugin", e.plugin());
            y.set("exclusions/" + k + "/reason", e.reason().name());
            y.set("exclusions/" + k + "/time", e.time());
        });
        unmatched.forEach((k, t) -> y.set("unmatched/" + k, t));
        AtomicFiles.writeString(file, y.saveToString());
    }

    private static YamlConfiguration yaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        return y;
    }
}
