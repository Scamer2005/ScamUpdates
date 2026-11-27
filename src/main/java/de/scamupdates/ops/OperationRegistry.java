package de.scamupdates.ops;

import de.scamupdates.logging.ScamLog;
import de.scamupdates.utils.AtomicFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Zentrale Vorgangsverwaltung: verhindert doppelte Vorgaenge (gleicher Typ+Schluessel bzw. exklusive Typen),
 * haelt Fortschritt/Status fuer GUIs und ueberlebt Neustarts (laufende Vorgaenge werden danach als INTERRUPTED
 * markiert und nie fortgesetzt oder doppelt ausgefuehrt).
 */
public final class OperationRegistry {
    private static final int KEEP = 50;
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final Path file;
    private final ScamLog log;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Operation> ops = new LinkedHashMap<>();
    private boolean loaded;

    public OperationRegistry(Path file, ScamLog log) {
        this.file = file;
        this.log = log;
    }

    /** Startet einen Vorgang oder liefert leer, wenn bereits ein aktiver Vorgang desselben Typs+Schluessels laeuft. */
    public synchronized Optional<Operation> begin(OpType type, String key, String actor) {
        load();
        for (Operation o : ops.values()) {
            if (o.isActive() && o.type() == type && o.key().equalsIgnoreCase(key)) return Optional.empty();
        }
        Operation op = new Operation(newId(), type, key, actor == null ? "system" : actor, System.currentTimeMillis());
        ops.put(op.id(), op);
        trim();
        save();
        return Optional.of(op);
    }

    public synchronized void progress(Operation op, int percent, String message) {
        op.progress(percent, message);
    }

    public synchronized void finish(Operation op, OpStatus status, String message) {
        if (!op.isActive()) return;
        op.set(status, status == OpStatus.SUCCEEDED ? 100 : op.percent(), message, System.currentTimeMillis());
        save();
    }

    public synchronized Optional<Operation> active(OpType type) {
        load();
        return ops.values().stream().filter(o -> o.isActive() && o.type() == type).findFirst();
    }

    public synchronized Optional<Operation> latest(OpType type) {
        load();
        return ops.values().stream().filter(o -> o.type() == type)
                .max(Comparator.comparingLong(Operation::startedAt));
    }

    public synchronized List<Operation> recent() {
        load();
        List<Operation> l = new ArrayList<>(ops.values());
        l.sort(Comparator.comparingLong(Operation::startedAt).reversed());
        return l;
    }

    /** Beim Start aufrufen: markiert verwaiste RUNNING-Vorgaenge als INTERRUPTED. */
    public synchronized int markInterrupted() {
        load();
        int n = 0;
        for (Operation o : ops.values()) {
            if (o.isActive()) {
                o.set(OpStatus.INTERRUPTED, o.percent(), "Server wurde waehrend des Vorgangs beendet", System.currentTimeMillis());
                log.warn("Vorgang " + o.id() + " (" + o.type() + ") wurde unterbrochen und wird nicht fortgesetzt.");
                n++;
            }
        }
        if (n > 0) save();
        return n;
    }

    private String newId() {
        String id;
        do {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            id = sb.toString();
        } while (ops.containsKey(id));
        return id;
    }

    private void trim() {
        if (ops.size() <= KEEP) return;
        List<Operation> finished = new ArrayList<>();
        for (Operation o : ops.values()) if (!o.isActive()) finished.add(o);
        finished.sort(Comparator.comparingLong(Operation::startedAt));
        for (Operation o : finished) {
            if (ops.size() <= KEEP) break;
            ops.remove(o.id());
        }
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (file == null || !Files.isRegularFile(file)) return;
        YamlConfiguration y = yaml();
        try {
            y.loadFromString(Files.readString(file));
        } catch (IOException | InvalidConfigurationException e) {
            log.error("operations.yml nicht lesbar", e);
            return;
        }
        ConfigurationSection sec = y.getConfigurationSection("ops");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            try {
                Operation o = new Operation(id, OpType.valueOf(s.getString("type", "CHECK")), s.getString("key", ""),
                        s.getString("actor", "system"), s.getLong("started", 0));
                o.set(OpStatus.valueOf(s.getString("status", "FAILED")), s.getInt("percent", 0),
                        s.getString("message", ""), s.getLong("finished", 0));
                ops.put(id, o);
            } catch (IllegalArgumentException e) {
                log.warn("Ungueltiger Eintrag in operations.yml uebersprungen: " + id);
            }
        }
    }

    private void save() {
        if (file == null) return;
        YamlConfiguration y = yaml();
        for (Operation o : ops.values()) {
            String b = "ops/" + o.id() + "/";
            y.set(b + "type", o.type().name());
            y.set(b + "key", o.key());
            y.set(b + "actor", o.actor());
            y.set(b + "started", o.startedAt());
            y.set(b + "status", o.status().name());
            y.set(b + "percent", o.percent());
            y.set(b + "message", log.redact(o.message()));
            y.set(b + "finished", o.finishedAt());
        }
        try {
            AtomicFiles.writeString(file, y.saveToString());
        } catch (IOException e) {
            log.error("operations.yml konnte nicht gespeichert werden", e);
        }
    }

    private static YamlConfiguration yaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        return y;
    }
}
