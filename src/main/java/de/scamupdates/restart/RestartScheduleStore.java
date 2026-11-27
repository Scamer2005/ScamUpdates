package de.scamupdates.restart;

import de.scamupdates.logging.ScamLog;
import de.scamupdates.utils.AtomicFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Strukturelle Grundlage fuer geplante und einmalige Neustarts:
 * verwaltet wiederkehrende Zeitplaene (mit naechstem Ausloesezeitpunkt) sowie einen
 * einmalig geplanten Neustart persistent in restarts.yml.
 */
public final class RestartScheduleStore {

    /**
     * @param dayOfWeek null = jeden Tag, sonst bestimmter Wochentag
     */
    public record ScheduleRule(String id, String label, DayOfWeek dayOfWeek, int hour, int minute,
                               boolean enabled, boolean requirePending) {
        public ScheduleRule {
            if (hour < 0 || hour > 23) throw new IllegalArgumentException("hour muss 0..23 sein: " + hour);
            if (minute < 0 || minute > 59) throw new IllegalArgumentException("minute muss 0..59 sein: " + minute);
        }

        public Optional<Long> nextTriggerMillis(Instant now, ZoneId zone) {
            if (!enabled) return Optional.empty();
            ZonedDateTime zdt = ZonedDateTime.ofInstant(now, zone);
            LocalTime targetTime = LocalTime.of(hour, minute);
            ZonedDateTime candidate = zdt.with(targetTime).withSecond(0).withNano(0);
            if (dayOfWeek == null) {
                if (!candidate.toInstant().isAfter(now)) candidate = candidate.plusDays(1);
                return Optional.of(candidate.toInstant().toEpochMilli());
            }
            for (int i = 0; i < 8; i++) {
                ZonedDateTime d = candidate.plusDays(i);
                if (d.getDayOfWeek() == dayOfWeek && d.toInstant().isAfter(now)) {
                    return Optional.of(d.toInstant().toEpochMilli());
                }
            }
            return Optional.empty();
        }
    }

    public record OneTimeRestart(String id, long triggerAtMillis, String actor, String reason, boolean requirePending) {
        public boolean isDue(long nowMillis) {
            return nowMillis >= triggerAtMillis;
        }
    }

    private final Path file;
    private final ScamLog log;
    private final Map<String, ScheduleRule> rules = new LinkedHashMap<>();
    private OneTimeRestart oneTime;
    private boolean loaded;

    public RestartScheduleStore(Path file, ScamLog log) {
        this.file = file;
        this.log = log;
    }

    public synchronized ScheduleRule addRule(String label, DayOfWeek dayOfWeek, int hour, int minute, boolean requirePending) {
        load();
        String id = UUID.randomUUID().toString().substring(0, 8);
        ScheduleRule rule = new ScheduleRule(id, label == null ? "" : label.trim(), dayOfWeek, hour, minute, true, requirePending);
        rules.put(id, rule);
        save();
        return rule;
    }

    public synchronized boolean setRuleEnabled(String id, boolean enabled) {
        load();
        ScheduleRule old = rules.get(id);
        if (old == null) return false;
        rules.put(id, new ScheduleRule(old.id(), old.label(), old.dayOfWeek(), old.hour(), old.minute(), enabled, old.requirePending()));
        save();
        return true;
    }

    public synchronized boolean removeRule(String id) {
        load();
        boolean removed = rules.remove(id) != null;
        if (removed) save();
        return removed;
    }

    public synchronized List<ScheduleRule> allRules() {
        load();
        return List.copyOf(rules.values());
    }

    public synchronized OneTimeRestart scheduleOneTime(long triggerAtMillis, String actor, String reason, boolean requirePending) {
        load();
        OneTimeRestart next = new OneTimeRestart(UUID.randomUUID().toString().substring(0, 8), triggerAtMillis,
                actor == null || actor.isBlank() ? "system" : actor.trim(),
                reason == null ? "" : reason.trim(), requirePending);
        this.oneTime = next;
        save();
        return next;
    }

    public synchronized boolean cancelOneTime() {
        load();
        if (this.oneTime == null) return false;
        this.oneTime = null;
        save();
        return true;
    }

    public synchronized Optional<OneTimeRestart> oneTime() {
        load();
        return Optional.ofNullable(oneTime);
    }

    /** Ermittelt den naechsten geplanten Ausloesezeitpunkt ueber alle aktiven Regeln und einen einmaligen Neustart. */
    public synchronized Optional<Long> nextTriggerMillis(Instant now, ZoneId zone) {
        load();
        List<Long> candidates = new ArrayList<>();
        if (oneTime != null) candidates.add(oneTime.triggerAtMillis());
        for (ScheduleRule r : rules.values()) {
            r.nextTriggerMillis(now, zone).ifPresent(candidates::add);
        }
        return candidates.stream().min(Comparator.naturalOrder());
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (file == null || !Files.isRegularFile(file)) return;
        YamlConfiguration y = yaml();
        try {
            y.loadFromString(Files.readString(file));
        } catch (IOException | InvalidConfigurationException e) {
            log.error("restarts.yml nicht lesbar", e);
            return;
        }
        ConfigurationSection ot = y.getConfigurationSection("one-time");
        if (ot != null && ot.contains("trigger-at")) {
            oneTime = new OneTimeRestart(
                    ot.getString("id", "once"),
                    ot.getLong("trigger-at", 0),
                    ot.getString("actor", "system"),
                    ot.getString("reason", ""),
                    ot.getBoolean("require-pending", false));
        }
        ConfigurationSection rs = y.getConfigurationSection("rules");
        if (rs != null) {
            for (String id : rs.getKeys(false)) {
                ConfigurationSection s = rs.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    String dowRaw = s.getString("day-of-week", "");
                    DayOfWeek dow = dowRaw == null || dowRaw.isBlank() ? null : DayOfWeek.valueOf(dowRaw);
                    rules.put(id, new ScheduleRule(id, s.getString("label", ""), dow,
                            s.getInt("hour", 4), s.getInt("minute", 0),
                            s.getBoolean("enabled", true), s.getBoolean("require-pending", false)));
                } catch (IllegalArgumentException e) {
                    log.warn("Ungueltige Neustart-Regel in restarts.yml uebersprungen: " + id);
                }
            }
        }
    }

    private void save() {
        if (file == null) return;
        YamlConfiguration y = yaml();
        if (oneTime != null) {
            y.set("one-time/id", oneTime.id());
            y.set("one-time/trigger-at", oneTime.triggerAtMillis());
            y.set("one-time/actor", oneTime.actor());
            y.set("one-time/reason", oneTime.reason());
            y.set("one-time/require-pending", oneTime.requirePending());
        }
        for (ScheduleRule r : rules.values()) {
            String b = "rules/" + r.id() + "/";
            y.set(b + "label", r.label());
            y.set(b + "day-of-week", r.dayOfWeek() == null ? "" : r.dayOfWeek().name());
            y.set(b + "hour", r.hour());
            y.set(b + "minute", r.minute());
            y.set(b + "enabled", r.enabled());
            y.set(b + "require-pending", r.requirePending());
        }
        try {
            AtomicFiles.writeString(file, y.saveToString());
        } catch (IOException e) {
            log.error("restarts.yml konnte nicht gespeichert werden", e);
        }
    }

    private static YamlConfiguration yaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        return y;
    }
}
