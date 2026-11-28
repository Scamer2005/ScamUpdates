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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Dauerhafte Speicherung wiederkehrender Neustart-Zeitplaene (stuendlich, taeglich, woechentlich,
 * mehrere Wochentage; Standardzeitzone Europe/Berlin) sowie einmaliger Neustarts in restarts.yml.
 */
public final class RestartScheduleStore {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Berlin");
    private static final int CONFLICT_MIN_GAP_MINUTES = 5;

    public enum Frequency { HOURLY, DAILY, WEEKLY, MULTI_DAY }

    public record ScheduleConflict(String conflictingRuleId, String conflictingLabel, String reason) {}

    public static final class ScheduleConflictException extends IllegalStateException {
        private final ScheduleConflict conflict;

        public ScheduleConflictException(ScheduleConflict conflict) {
            super(conflict.reason());
            this.conflict = conflict;
        }

        public ScheduleConflict conflict() { return conflict; }
    }

    public record ScheduleRule(String id, String label, Frequency frequency, Set<DayOfWeek> daysOfWeek,
                               int hour, int minute, boolean enabled, boolean requirePending) {

        public ScheduleRule {
            if (hour < 0 || hour > 23) throw new IllegalArgumentException("hour muss 0..23 sein: " + hour);
            if (minute < 0 || minute > 59) throw new IllegalArgumentException("minute muss 0..59 sein: " + minute);
            Set<DayOfWeek> cleanDays = (daysOfWeek == null || daysOfWeek.isEmpty())
                    ? Set.of()
                    : Collections.unmodifiableSet(EnumSet.copyOf(daysOfWeek));
            if (frequency == null) {
                frequency = cleanDays.isEmpty() ? Frequency.DAILY : (cleanDays.size() == 1 ? Frequency.WEEKLY : Frequency.MULTI_DAY);
            }
            if ((frequency == Frequency.WEEKLY || frequency == Frequency.MULTI_DAY) && cleanDays.isEmpty()) {
                throw new IllegalArgumentException("Mindestens ein Wochentag ist erforderlich fuer " + frequency);
            }
            if (frequency == Frequency.WEEKLY && cleanDays.size() > 1) {
                frequency = Frequency.MULTI_DAY;
            }
            if (frequency == Frequency.DAILY || frequency == Frequency.HOURLY) {
                cleanDays = Set.of();
            }
            daysOfWeek = cleanDays;
            label = label == null ? "" : label.trim();
        }

        /** Rueckwaertskompatibler Konstruktor (Schritt 2). */
        public ScheduleRule(String id, String label, DayOfWeek dayOfWeek, int hour, int minute,
                            boolean enabled, boolean requirePending) {
            this(id, label, dayOfWeek == null ? Frequency.DAILY : Frequency.WEEKLY,
                    dayOfWeek == null ? Set.of() : Set.of(dayOfWeek), hour, minute, enabled, requirePending);
        }

        public DayOfWeek dayOfWeek() {
            return daysOfWeek.isEmpty() ? null : daysOfWeek.iterator().next();
        }

        public Optional<Long> nextTriggerMillis(Instant now, ZoneId zone) {
            if (!enabled) return Optional.empty();
            ZoneId z = zone == null ? DEFAULT_ZONE : zone;
            ZonedDateTime zdt = ZonedDateTime.ofInstant(now, z);
            return switch (frequency) {
                case HOURLY -> {
                    ZonedDateTime base = zdt.withMinute(minute).withSecond(0).withNano(0);
                    for (int i = 0; i <= 4; i++) {
                        ZonedDateTime cand = base.plusHours(i).withMinute(minute).withSecond(0).withNano(0);
                        if (cand.toInstant().isAfter(now)) {
                            yield Optional.of(cand.toInstant().toEpochMilli());
                        }
                    }
                    yield Optional.empty();
                }
                case DAILY -> {
                    LocalDate d = zdt.toLocalDate();
                    ZonedDateTime cand = d.atTime(hour, minute).atZone(z);
                    if (!cand.toInstant().isAfter(now)) {
                        cand = d.plusDays(1).atTime(hour, minute).atZone(z);
                    }
                    yield Optional.of(cand.toInstant().toEpochMilli());
                }
                case WEEKLY, MULTI_DAY -> {
                    for (int i = 0; i <= 8; i++) {
                        LocalDate d = zdt.toLocalDate().plusDays(i);
                        if (daysOfWeek.contains(d.getDayOfWeek())) {
                            ZonedDateTime cand = d.atTime(hour, minute).atZone(z);
                            if (cand.toInstant().isAfter(now)) {
                                yield Optional.of(cand.toInstant().toEpochMilli());
                            }
                        }
                    }
                    yield Optional.empty();
                }
            };
        }
    }

    public record OneTimeRestart(String id, long triggerAtMillis, long createdAtMillis,
                                 String actor, String reason, boolean requirePending) {
        public OneTimeRestart(String id, long triggerAtMillis, String actor, String reason, boolean requirePending) {
            this(id, triggerAtMillis, System.currentTimeMillis(), actor, reason, requirePending);
        }

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
        return addRule(label, dayOfWeek == null ? Frequency.DAILY : Frequency.WEEKLY,
                dayOfWeek == null ? Set.of() : Set.of(dayOfWeek), hour, minute, requirePending);
    }

    public synchronized ScheduleRule addRule(String label, Frequency frequency, Set<DayOfWeek> daysOfWeek,
                                             int hour, int minute, boolean requirePending) {
        load();
        ScheduleRule draft = new ScheduleRule(UUID.randomUUID().toString().substring(0, 8), label,
                frequency, daysOfWeek, hour, minute, true, requirePending);
        Optional<ScheduleConflict> conflict = findConflict(draft, null);
        if (conflict.isPresent()) {
            throw new ScheduleConflictException(conflict.get());
        }
        rules.put(draft.id(), draft);
        save();
        return draft;
    }

    public synchronized ScheduleRule updateRule(String id, String label, Frequency frequency, Set<DayOfWeek> daysOfWeek,
                                                int hour, int minute, boolean enabled, boolean requirePending) {
        load();
        ScheduleRule existing = rules.get(id);
        if (existing == null) throw new IllegalArgumentException("Unbekannte Neustart-Regel: " + id);
        ScheduleRule updated = new ScheduleRule(id, label, frequency, daysOfWeek, hour, minute, enabled, requirePending);
        if (enabled) {
            Optional<ScheduleConflict> conflict = findConflict(updated, id);
            if (conflict.isPresent()) throw new ScheduleConflictException(conflict.get());
        }
        rules.put(id, updated);
        save();
        return updated;
    }

    /** Erkennt doppelte oder zeitlich widerspruechliche Neustartplaene. */
    public synchronized Optional<ScheduleConflict> findConflict(ScheduleRule candidate, String ignoreRuleId) {
        load();
        Objects.requireNonNull(candidate, "candidate");
        for (ScheduleRule other : rules.values()) {
            if (ignoreRuleId != null && ignoreRuleId.equals(other.id())) continue;
            if (!other.enabled()) continue;
            Optional<ScheduleConflict> c = detectConflictBetween(candidate, other);
            if (c.isPresent()) return c;
        }
        return Optional.empty();
    }

    private static Optional<ScheduleConflict> detectConflictBetween(ScheduleRule a, ScheduleRule b) {
        String label = b.label().isBlank() ? b.id() : b.label();
        if (a.frequency() == Frequency.HOURLY && b.frequency() == Frequency.HOURLY) {
            int diff = circularMinuteDiff(a.minute(), b.minute());
            if (diff == 0) return Optional.of(new ScheduleConflict(b.id(), label, "Doppelter stuendlicher Neustart zur Minute :" + String.format("%02d", a.minute())));
            if (diff < CONFLICT_MIN_GAP_MINUTES) return Optional.of(new ScheduleConflict(b.id(), label, "Zu geringer Abstand (< " + CONFLICT_MIN_GAP_MINUTES + " Min.) zum stuendlichen Plan '" + label + "'"));
            return Optional.empty();
        }
        if (a.frequency() == Frequency.HOURLY || b.frequency() == Frequency.HOURLY) {
            int hourlyMin = a.frequency() == Frequency.HOURLY ? a.minute() : b.minute();
            int otherMin = a.frequency() == Frequency.HOURLY ? b.minute() : a.minute();
            if (circularMinuteDiff(hourlyMin, otherMin) < CONFLICT_MIN_GAP_MINUTES) {
                return Optional.of(new ScheduleConflict(b.id(), label, "Ueberschneidung mit stuendlichem Plan '" + label + "'"));
            }
            return Optional.empty();
        }
        Set<DayOfWeek> daysA = effectiveDays(a);
        Set<DayOfWeek> daysB = effectiveDays(b);
        boolean sharesDay = false;
        for (DayOfWeek d : daysA) {
            if (daysB.contains(d)) { sharesDay = true; break; }
        }
        if (!sharesDay) return Optional.empty();

        int minA = a.hour() * 60 + a.minute();
        int minB = b.hour() * 60 + b.minute();
        int diff = Math.abs(minA - minB);
        diff = Math.min(diff, 1440 - diff);
        if (diff == 0) {
            return Optional.of(new ScheduleConflict(b.id(), label,
                    String.format("Doppelter Neustartplan zur selben Uhrzeit (%02d:%02d) wie '%s'", a.hour(), a.minute(), label)));
        }
        if (diff < CONFLICT_MIN_GAP_MINUTES) {
            return Optional.of(new ScheduleConflict(b.id(), label,
                    "Widerspruechlicher/zu naher Plan (< " + CONFLICT_MIN_GAP_MINUTES + " Min. Abstand) zu '" + label + "'"));
        }
        return Optional.empty();
    }

    private static int circularMinuteDiff(int m1, int m2) {
        int d = Math.abs(m1 - m2);
        return Math.min(d, 60 - d);
    }

    private static Set<DayOfWeek> effectiveDays(ScheduleRule r) {
        return r.frequency() == Frequency.DAILY ? EnumSet.allOf(DayOfWeek.class) : r.daysOfWeek();
    }

    public synchronized boolean setRuleEnabled(String id, boolean enabled) {
        load();
        ScheduleRule old = rules.get(id);
        if (old == null) return false;
        ScheduleRule next = new ScheduleRule(old.id(), old.label(), old.frequency(), old.daysOfWeek(),
                old.hour(), old.minute(), enabled, old.requirePending());
        if (enabled) {
            Optional<ScheduleConflict> conflict = findConflict(next, id);
            if (conflict.isPresent()) throw new ScheduleConflictException(conflict.get());
        }
        rules.put(id, next);
        save();
        return true;
    }

    public synchronized boolean removeRule(String id) {
        load();
        boolean removed = rules.remove(id) != null;
        if (removed) save();
        return removed;
    }

    public synchronized Optional<ScheduleRule> rule(String id) {
        load();
        return id == null ? Optional.empty() : Optional.ofNullable(rules.get(id));
    }

    public synchronized List<ScheduleRule> allRules() {
        load();
        return List.copyOf(rules.values());
    }

    public synchronized OneTimeRestart scheduleOneTime(long triggerAtMillis, String actor, String reason, boolean requirePending) {
        return scheduleOneTime(triggerAtMillis, System.currentTimeMillis(), actor, reason, requirePending);
    }

    public synchronized OneTimeRestart scheduleOneTime(long triggerAtMillis, long createdAtMillis, String actor, String reason, boolean requirePending) {
        load();
        OneTimeRestart next = new OneTimeRestart(UUID.randomUUID().toString().substring(0, 8), triggerAtMillis,
                createdAtMillis,
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
        ZoneId z = zone == null ? DEFAULT_ZONE : zone;
        List<Long> candidates = new ArrayList<>();
        if (oneTime != null) candidates.add(oneTime.triggerAtMillis());
        for (ScheduleRule r : rules.values()) {
            r.nextTriggerMillis(now, z).ifPresent(candidates::add);
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
                    ot.getLong("created-at", 0),
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
                    Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
                    for (String d : s.getStringList("days-of-week")) {
                        if (d != null && !d.isBlank()) days.add(DayOfWeek.valueOf(d.trim().toUpperCase(java.util.Locale.ROOT)));
                    }
                    String dowRaw = s.getString("day-of-week", "");
                    if (days.isEmpty() && dowRaw != null && !dowRaw.isBlank()) {
                        days.add(DayOfWeek.valueOf(dowRaw.trim().toUpperCase(java.util.Locale.ROOT)));
                    }
                    String freqRaw = s.getString("frequency", "");
                    Frequency freq;
                    if (freqRaw != null && !freqRaw.isBlank()) {
                        freq = Frequency.valueOf(freqRaw.trim().toUpperCase(java.util.Locale.ROOT));
                    } else {
                        freq = days.isEmpty() ? Frequency.DAILY : (days.size() == 1 ? Frequency.WEEKLY : Frequency.MULTI_DAY);
                    }
                    rules.put(id, new ScheduleRule(id, s.getString("label", ""), freq, days,
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
            y.set("one-time/created-at", oneTime.createdAtMillis());
            y.set("one-time/actor", oneTime.actor());
            y.set("one-time/reason", oneTime.reason());
            y.set("one-time/require-pending", oneTime.requirePending());
        }
        for (ScheduleRule r : rules.values()) {
            String b = "rules/" + r.id() + "/";
            y.set(b + "label", r.label());
            y.set(b + "frequency", r.frequency().name());
            y.set(b + "day-of-week", r.dayOfWeek() == null ? "" : r.dayOfWeek().name());
            List<String> dayNames = r.daysOfWeek().stream().map(DayOfWeek::name).toList();
            y.set(b + "days-of-week", dayNames);
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
