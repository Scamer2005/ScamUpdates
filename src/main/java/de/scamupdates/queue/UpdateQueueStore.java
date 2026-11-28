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
import java.util.UUID;

/**
 * Persistente Update-Warteschlange in queue.yml.
 * Verhindert doppelte Eintraege (case-insensitiv pro Plugin) und vergibt pro Eintrag eine eindeutige ID.
 * Uebersteht Serverneustarts und temporaere Internetausfaelle; fehlerhafte Eintraege werden nachvollziehbar
 * uebersprungen und protokolliert.
 */
public final class UpdateQueueStore {

    public record Entry(String id, String plugin, String installedVersion, String targetVersion, String sourceId,
                        String lastStatus, String notifiedVersion, long snoozedUntil, String addedBy, long addedAt) {

        /** Rueckwaertskompatibler Konstruktor fuer Schritt-2-Aufrufer. */
        public Entry(String plugin, String targetVersion, String sourceId, String addedBy, long addedAt) {
            this(UUID.randomUUID().toString(), plugin, "", targetVersion, sourceId, "QUEUED", "", 0L, addedBy, addedAt);
        }
    }

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
        enqueue(candidate.plugin().name(), candidate.fromVersion(), candidate.toVersion(),
                candidate.project().sourceId(), "UPDATE_AVAILABLE", actor);
    }

    public synchronized void enqueue(String plugin, String targetVersion, String sourceId, String actor) {
        enqueue(plugin, "", targetVersion, sourceId, "QUEUED", actor);
    }

    /** Merkt ein Plugin dauerhaft vor (auch wenn aktuell noch kein Update verfuegbar ist). Verhindert Duplikate. */
    public synchronized Entry enqueue(String plugin, String installedVersion, String targetVersion,
                                      String sourceId, String status, String actor) {
        load();
        if (plugin == null || plugin.isBlank()) return null;
        String cleanName = plugin.trim();
        String key = key(cleanName);
        Entry existing = entries.get(key);
        String id = existing != null && existing.id() != null && !existing.id().isBlank()
                ? existing.id()
                : UUID.randomUUID().toString();
        String prevNotified = existing == null ? "" : safe(existing.notifiedVersion());
        long prevSnooze = existing == null ? 0L : existing.snoozedUntil();
        String nextStatus = status == null || status.isBlank() ? "QUEUED" : status.trim();
        Entry entry = new Entry(id, cleanName, safe(installedVersion), safe(targetVersion), safe(sourceId),
                nextStatus, prevNotified, prevSnooze,
                actor == null || actor.isBlank() ? "system" : actor.trim(),
                System.currentTimeMillis());
        entries.put(key, entry);
        save();
        return entry;
    }

    /** Fuegt einen Eintrag nur hinzu, wenn das Plugin noch nicht in der Warteschlange ist. */
    public synchronized boolean enqueueIfAbsent(String plugin, String installedVersion, String sourceId, String status, String actor) {
        load();
        if (plugin == null || plugin.isBlank()) return false;
        if (entries.containsKey(key(plugin))) return false;
        enqueue(plugin, installedVersion, "", sourceId, status, actor);
        return true;
    }

    /** Aktualisiert Status/Zielversion eines bestehenden Eintrags nach einer Update-Pruefung. */
    public synchronized void updateObservation(String plugin, String installedVersion, String targetVersion,
                                               String sourceId, String status) {
        load();
        if (plugin == null || plugin.isBlank()) return;
        String key = key(plugin);
        Entry old = entries.get(key);
        if (old == null) return;
        String nextInst = installedVersion == null || installedVersion.isBlank() ? old.installedVersion() : installedVersion.trim();
        String nextTarget = targetVersion == null ? old.targetVersion() : targetVersion.trim();
        String nextSrc = sourceId == null || sourceId.isBlank() ? old.sourceId() : sourceId.trim();
        String nextStatus = status == null || status.isBlank() ? old.lastStatus() : status.trim();
        if (nextInst.equals(old.installedVersion()) && nextTarget.equals(old.targetVersion())
                && nextSrc.equals(old.sourceId()) && nextStatus.equals(old.lastStatus())) {
            return;
        }
        entries.put(key, new Entry(old.id(), old.plugin(), nextInst, nextTarget, nextSrc,
                nextStatus, old.notifiedVersion(), old.snoozedUntil(), old.addedBy(), old.addedAt()));
        save();
    }

    /** Markiert "Spaeter": Eintrag bleibt dauerhaft erhalten, Benachrichtigung wird bis untilMillis pausiert. */
    public synchronized boolean snooze(String plugin, long untilMillis) {
        load();
        if (plugin == null || plugin.isBlank()) return false;
        String key = key(plugin);
        Entry old = entries.get(key);
        if (old == null) return false;
        entries.put(key, new Entry(old.id(), old.plugin(), old.installedVersion(), old.targetVersion(),
                old.sourceId(), old.lastStatus(), old.notifiedVersion(), Math.max(0L, untilMillis), old.addedBy(), old.addedAt()));
        save();
        return true;
    }

    /** Merkt, welche verfuegbare Version bereits gemeldet wurde, um Spam zu vermeiden. */
    public synchronized boolean markNotified(String plugin, String version) {
        load();
        if (plugin == null || plugin.isBlank()) return false;
        String key = key(plugin);
        Entry old = entries.get(key);
        if (old == null) return false;
        String v = safe(version);
        if (v.equals(old.notifiedVersion())) return false;
        entries.put(key, new Entry(old.id(), old.plugin(), old.installedVersion(), old.targetVersion(),
                old.sourceId(), old.lastStatus(), v, old.snoozedUntil(), old.addedBy(), old.addedAt()));
        save();
        return true;
    }

    /** Setzt nach einem Serverneustart veraltete AMBIGUOUS-Benachrichtigungsmarker zurueck, damit neue Treffer gemeldet werden. */
    public synchronized int resetAmbiguousNotifications() {
        load();
        int changed = 0;
        for (Map.Entry<String, Entry> me : entries.entrySet()) {
            Entry e = me.getValue();
            if ("AMBIGUOUS".equals(e.notifiedVersion())) {
                me.setValue(new Entry(e.id(), e.plugin(), e.installedVersion(), e.targetVersion(),
                        e.sourceId(), e.lastStatus(), "", e.snoozedUntil(), e.addedBy(), e.addedAt()));
                changed++;
            }
        }
        if (changed > 0) save();
        return changed;
    }

    /** "Nicht mehr erinnern" — entfernt den Eintrag dauerhaft. */
    public synchronized boolean dismiss(String plugin) {
        return remove(plugin);
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
        return plugin != null && !plugin.isBlank() && entries.containsKey(key(plugin));
    }

    public synchronized Optional<Entry> get(String plugin) {
        load();
        return plugin == null || plugin.isBlank() ? Optional.empty() : Optional.ofNullable(entries.get(key(plugin)));
    }

    public synchronized Optional<Entry> getById(String id) {
        load();
        if (id == null || id.isBlank()) return Optional.empty();
        for (Entry e : entries.values()) {
            if (id.trim().equals(e.id())) return Optional.of(e);
        }
        return Optional.empty();
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

    /** Entfernt Plugins aus der Warteschlange (z. B. nach erfolgreichem Download). */
    public synchronized int removeAll(Collection<String> pluginNames) {
        load();
        if (pluginNames == null || pluginNames.isEmpty()) return 0;
        int n = 0;
        for (String p : pluginNames) {
            if (p != null && !p.isBlank() && entries.remove(key(p)) != null) n++;
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
        boolean normalized = false;
        for (String k : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(k);
            if (s == null) {
                log.warn("Fehlerhafter Eintrag in queue.yml uebersprungen: " + k);
                continue;
            }
            String plugin = safe(s.getString("plugin", k));
            if (plugin.isEmpty()) {
                log.warn("Leerer Pluginname in queue.yml uebersprungen: " + k);
                continue;
            }
            String canonical = key(plugin);
            if (entries.containsKey(canonical)) {
                log.warn("Doppelter Queue-Eintrag fuer '" + plugin + "' in queue.yml ignoriert.");
                normalized = true;
                continue;
            }
            String id = safe(s.getString("id", ""));
            if (id.isEmpty()) {
                id = UUID.randomUUID().toString();
                normalized = true;
            }
            long snoozedUntil = Math.max(0L, s.getLong("snoozed-until", 0L));
            long addedAt = Math.max(0L, s.getLong("added-at", 0L));
            entries.put(canonical, new Entry(
                    id,
                    plugin,
                    safe(s.getString("installed-version", "")),
                    safe(s.getString("target-version", "")),
                    safe(s.getString("source", "")),
                    safe(s.getString("last-status", "QUEUED")),
                    safe(s.getString("notified-version", "")),
                    snoozedUntil,
                    safe(s.getString("added-by", "system")),
                    addedAt));
        }
        if (normalized) save();
    }

    private void save() {
        if (file == null) return;
        YamlConfiguration y = yaml();
        for (Entry e : entries.values()) {
            String b = "queue/" + key(e.plugin()) + "/";
            y.set(b + "id", e.id());
            y.set(b + "plugin", e.plugin());
            y.set(b + "installed-version", e.installedVersion());
            y.set(b + "target-version", e.targetVersion());
            y.set(b + "source", e.sourceId());
            y.set(b + "last-status", e.lastStatus());
            y.set(b + "notified-version", e.notifiedVersion());
            y.set(b + "snoozed-until", e.snoozedUntil());
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
