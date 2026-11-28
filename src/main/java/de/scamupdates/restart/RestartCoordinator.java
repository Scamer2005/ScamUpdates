package de.scamupdates.restart;

import de.scamupdates.config.ConfigManager;
import de.scamupdates.history.HistoryEntry;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.state.StateManager;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Steuert geplante und einmalige Neustart-Countdowns zentral und garantiert,
 * dass niemals zwei Neustart-Countdowns gleichzeitig laufen.
 * Arbeitet ohne Bukkit-Direktabhaengigkeit und nutzt konfigurierbare Warnzeiten sowie
 * RestartService.readiness() vor jeder Ausfuehrung.
 */
public final class RestartCoordinator {

    public enum Source { ONE_TIME, SCHEDULED }

    public enum Preset {
        IMMEDIATE(0, "Sofort"),
        SECONDS_10(10, "10 Sekunden"),
        MINUTE_1(60, "1 Minute"),
        MINUTES_5(300, "5 Minuten"),
        MINUTES_10(600, "10 Minuten"),
        MINUTES_30(1800, "30 Minuten"),
        HOUR_1(3600, "1 Stunde");

        public final int seconds;
        public final String displayName;

        Preset(int seconds, String displayName) {
            this.seconds = seconds;
            this.displayName = displayName;
        }
    }

    public record ActiveCountdown(String id, Source source, String ruleId, long targetMillis,
                                  String actor, String reason, boolean requirePending) {
        public long remainingMillis(long nowMillis) {
            return Math.max(0L, targetMillis - nowMillis);
        }

        public long remainingSeconds(long nowMillis) {
            long rem = remainingMillis(nowMillis);
            return rem <= 0 ? 0L : (rem + 999L) / 1000L;
        }
    }

    public interface Listener {
        default void onWarning(ActiveCountdown countdown, int remainingSeconds) {}
        default void onExecutionFailed(String actor, String detail) {}
        default void onCancelled(ActiveCountdown previous, String actor) {}
    }

    private final RestartScheduleStore store;
    private final ConfigManager config;
    private final StateManager state;
    private final ScamLog log;
    private volatile RestartService restartService;
    private volatile HistoryStore history;
    private volatile Listener listener = new Listener() {};

    private ActiveCountdown active;
    private final Set<Integer> firedOrSkippedWarnings = new HashSet<>();
    private final Set<String> handledScheduleSlots = new HashSet<>();
    private final AtomicBoolean executing = new AtomicBoolean(false);
    private long lastTickMillis;

    public RestartCoordinator(RestartScheduleStore store, ConfigManager config, StateManager state, ScamLog log) {
        this(store, config, state, log, System.currentTimeMillis());
    }

    public RestartCoordinator(RestartScheduleStore store, ConfigManager config, StateManager state,
                              ScamLog log, long initialNowMillis) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.state = Objects.requireNonNull(state, "state");
        this.log = Objects.requireNonNull(log, "log");
        this.lastTickMillis = initialNowMillis;
    }

    public void setRestartService(RestartService restartService) { this.restartService = restartService; }
    public void setHistory(HistoryStore history) { this.history = history; }
    public void setListener(Listener listener) { this.listener = listener == null ? new Listener() {} : listener; }

    public ZoneId zone() { return config.restartTimezone(); }

    public synchronized Optional<ActiveCountdown> activeCountdown() {
        return Optional.ofNullable(active);
    }

    /**
     * Prioritaetsregel:
     * 1. Es existiert zu jedem Zeitpunkt hoechstens ein aktiver Neustart-Countdown.
     * 2. Explizite einmalige Neustarts (ONE_TIME) haben stets Vorrang vor wiederkehrenden Zeitplaenen (SCHEDULED).
     * 3. Wird ein aktiver SCHEDULED-Countdown durch ONE_TIME/SOFORT verdraengt, wird der verdraengte Slot
     *    als behandelt markiert, protokolliert und ueber Listener.onCancelled gemeldet.
     */
    public synchronized Optional<Long> nextEffectiveTriggerMillis(long nowMillis) {
        if (active != null) {
            return Optional.of(active.targetMillis());
        }
        ZoneId z = zone();
        Long best = null;
        Optional<RestartScheduleStore.OneTimeRestart> ot = store.oneTime();
        if (ot.isPresent()) {
            best = ot.get().triggerAtMillis();
        }
        Instant probe = Instant.ofEpochMilli(nowMillis);
        for (RestartScheduleStore.ScheduleRule r : store.allRules()) {
            if (!r.enabled()) continue;
            Instant cursor = probe;
            for (int hop = 0; hop < 3; hop++) {
                Optional<Long> next = r.nextTriggerMillis(cursor, z);
                if (next.isEmpty()) break;
                long t = next.get();
                if (!handledScheduleSlots.contains(slotKey(r.id(), t))) {
                    if (best == null || t < best) best = t;
                    break;
                }
                cursor = Instant.ofEpochMilli(t);
            }
        }
        return Optional.ofNullable(best);
    }

    /** Reagiert auf Config-Reloads (z. B. restarts.enabled: false oder geaenderte Zeitzone). */
    public void onConfigReloaded(long nowMillis) {
        if (!config.restartsEnabled()) {
            cancelActive("config-reload");
            return;
        }
        synchronized (this) {
            this.lastTickMillis = nowMillis;
            if (active != null && active.source() == Source.SCHEDULED) {
                log.info("Aktiver geplanter Countdown ('" + active.reason() + "') wird nach Config-Reload neu bewertet.");
                active = null;
                firedOrSkippedWarnings.clear();
            }
        }
    }

    /** Synchronisiert persistierte einmalige Neustarts nach einem Serverneustart. */
    public synchronized void reconcileOnStartup(long nowMillis) {
        this.lastTickMillis = nowMillis;
        Optional<RestartScheduleStore.OneTimeRestart> ot = store.oneTime();
        if (ot.isEmpty()) return;
        RestartScheduleStore.OneTimeRestart r = ot.get();
        if (r.isDue(nowMillis)) {
            store.cancelOneTime();
            log.warn("Abgelaufener einmaliger Neustart aus restarts.yml (" + r.id() + ") wurde nach dem Serverstart verworfen.");
            record("restart-schedule", "INFO", r.actor(), "Abgelaufener einmaliger Neustart nach Neustart bereinigt");
            return;
        }
        armInternal(new ActiveCountdown(r.id(), Source.ONE_TIME, null, r.triggerAtMillis(), r.actor(), r.reason(), r.requirePending()),
                nowMillis, true);
        log.info("Einmaliger Neustart wiederhergestellt: verbleibend " + formatDuration(active.remainingSeconds(nowMillis)) + ".");
    }

    /** Plant einen vordefinierten einmaligen Neustart. "Sofort" fuehrt ohne Warnphase direkt aus. */
    public CompletableFuture<RestartService.Outcome> schedulePreset(Preset preset, String actor, String reason, long nowMillis) {
        Objects.requireNonNull(preset, "preset");
        if (preset == Preset.IMMEDIATE) {
            return triggerImmediate(actor, reason == null || reason.isBlank() ? "Sofortiger Neustart" : reason);
        }
        scheduleDelayed(preset.seconds, actor, reason == null || reason.isBlank() ? preset.displayName : reason, nowMillis);
        return CompletableFuture.completedFuture(null);
    }

    /** Plant einen benutzerdefinierten einmaligen Neustart (Stunden, Minuten, Sekunden). */
    public synchronized ActiveCountdown scheduleCustom(int hours, int minutes, int seconds, String actor, String reason, long nowMillis) {
        if (hours < 0 || minutes < 0 || seconds < 0) throw new IllegalArgumentException("Zeit darf nicht negativ sein");
        long totalSeconds = hours * 3600L + minutes * 60L + seconds;
        if (totalSeconds <= 0) throw new IllegalArgumentException("Benutzerdefinierte Zeit muss groesser als 0 Sekunden sein");
        return scheduleDelayed(totalSeconds, actor, reason, nowMillis);
    }

    public synchronized ActiveCountdown scheduleDelayed(long delaySeconds, String actor, String reason, long nowMillis) {
        if (delaySeconds <= 0) throw new IllegalArgumentException("Verzoegerung muss > 0s sein");
        if (executing.get()) throw new IllegalStateException("Ein Neustart wird bereits ausgefuehrt");
        ActiveCountdown displaced = displaceExistingForOverride(actor, "Einmaliger Neustart");
        long target = nowMillis + delaySeconds * 1000L;
        RestartScheduleStore.OneTimeRestart ot = store.scheduleOneTime(target, nowMillis, actor,
                reason == null ? "" : reason, false);
        ActiveCountdown next = new ActiveCountdown(ot.id(), Source.ONE_TIME, null, target, ot.actor(), ot.reason(), false);
        armInternal(next, nowMillis, false);
        log.info("Einmaliger Neustart geplant von " + ot.actor() + " in " + formatDuration(delaySeconds) + ".");
        record("restart-onetime", "INFO", ot.actor(), "Einmaliger Neustart geplant in " + formatDuration(delaySeconds));
        if (displaced != null && displaced.source() == Source.SCHEDULED) {
            listener.onCancelled(displaced, ot.actor());
        }
        return next;
    }

    /** Loest sofort einen Neustart ohne vorherige Warnphase aus und ersetzt jeden laufenden Countdown. */
    public CompletableFuture<RestartService.Outcome> triggerImmediate(String actor, String reason) {
        String cleanActor = actor == null || actor.isBlank() ? "system" : actor;
        ActiveCountdown displaced;
        synchronized (this) {
            if (!executing.compareAndSet(false, true)) {
                CompletableFuture<RestartService.Outcome> f = new CompletableFuture<>();
                f.complete(new RestartService.Outcome(RestartService.Result.BUSY, List.of("restart")));
                return f;
            }
            displaced = displaceExistingForOverride(cleanActor, "Sofortiger Neustart");
            firedOrSkippedWarnings.clear();
            store.cancelOneTime();
        }
        if (displaced != null && displaced.source() == Source.SCHEDULED) {
            listener.onCancelled(displaced, cleanActor);
        }
        return executeNow(cleanActor, false,
                reason == null || reason.isBlank() ? "Sofortiger Neustart" : reason);
    }

    private ActiveCountdown displaceExistingForOverride(String actor, String by) {
        ActiveCountdown prev = this.active;
        if (prev == null) return null;
        String cleanActor = actor == null || actor.isBlank() ? "system" : actor;
        if (prev.source() == Source.SCHEDULED && prev.ruleId() != null) {
            handledScheduleSlots.add(slotKey(prev.ruleId(), prev.targetMillis()));
            log.info("Geplanter Neustart ('" + prev.reason() + "') von " + cleanActor + " durch '" + by + "' verdraengt.");
            record("restart-cancel", "INFO", cleanActor, "Geplanter Neustart durch '" + by + "' verdraengt");
        } else {
            log.info("Bisheriger einmaliger Neustart (" + prev.id() + ") von " + cleanActor + " durch '" + by + "' ersetzt.");
            record("restart-cancel", "INFO", cleanActor, "Vorheriger einmaliger Neustart ersetzt");
        }
        this.active = null;
        this.firedOrSkippedWarnings.clear();
        return prev;
    }

    public boolean cancelActive(String actor) {
        ActiveCountdown prev;
        synchronized (this) {
            if (active == null && store.oneTime().isEmpty()) return false;
            prev = active;
            if (prev != null && prev.source() == Source.SCHEDULED && prev.ruleId() != null) {
                handledScheduleSlots.add(slotKey(prev.ruleId(), prev.targetMillis()));
            }
            active = null;
            firedOrSkippedWarnings.clear();
            store.cancelOneTime();
        }
        log.info("Geplanter Neustart abgebrochen von " + actor + ".");
        record("restart-cancel", "INFO", actor, "Laufender Neustart-Countdown abgebrochen");
        listener.onCancelled(prev, actor);
        return true;
    }

    /** Regelmaessiger Takt (z. B. 1x pro Sekunde auf dem Hauptthread oder im Test). */
    public void tick(long nowMillis) {
        if (!state.isOperational() || executing.get()) {
            synchronized (this) { this.lastTickMillis = nowMillis; }
            return;
        }
        ActiveCountdown dueToRun = null;
        List<Integer> warningsToFire = List.of();
        ActiveCountdown warningTarget = null;

        synchronized (this) {
            if (active == null) {
                armNextScheduledRuleIfWithinWindow(nowMillis);
            }
            this.lastTickMillis = nowMillis;
            if (active != null) {
                long remainingMs = active.targetMillis() - nowMillis;
                if (remainingMs <= 0) {
                    if (executing.compareAndSet(false, true)) {
                        dueToRun = active;
                        if (dueToRun.source() == Source.ONE_TIME) store.cancelOneTime();
                        if (dueToRun.source() == Source.SCHEDULED && dueToRun.ruleId() != null) {
                            handledScheduleSlots.add(slotKey(dueToRun.ruleId(), dueToRun.targetMillis()));
                        }
                        active = null;
                        firedOrSkippedWarnings.clear();
                    }
                } else {
                    int remSec = (int) ((remainingMs + 999L) / 1000L);
                    warningsToFire = collectDueWarnings(remSec);
                    warningTarget = active;
                }
            }
        }

        if (warningTarget != null && !warningsToFire.isEmpty()) {
            for (int sec : warningsToFire) {
                listener.onWarning(warningTarget, sec);
            }
        }
        if (dueToRun != null) {
            executeNow(dueToRun.actor(), dueToRun.requirePending(), dueToRun.reason());
        }
    }

    private void armNextScheduledRuleIfWithinWindow(long nowMillis) {
        List<Integer> warnCfg = config.restartWarningSeconds();
        long maxWarnMs = warnCfg.stream().mapToLong(i -> Math.max(0, i) * 1000L).max().orElse(0L);
        long windowEnd = nowMillis + Math.max(1000L, maxWarnMs);
        Instant probeFrom = Instant.ofEpochMilli(Math.max(0L, Math.min(lastTickMillis, nowMillis) - 1L));
        ZoneId z = zone();

        RestartScheduleStore.ScheduleRule earliestRule = null;
        long earliestTime = Long.MAX_VALUE;
        for (RestartScheduleStore.ScheduleRule r : store.allRules()) {
            if (!r.enabled()) continue;
            Instant cursor = probeFrom;
            for (int hop = 0; hop < 3; hop++) {
                Optional<Long> next = r.nextTriggerMillis(cursor, z);
                if (next.isEmpty()) break;
                long t = next.get();
                if (!handledScheduleSlots.contains(slotKey(r.id(), t))) {
                    if (t <= windowEnd && t < earliestTime) {
                        earliestTime = t;
                        earliestRule = r;
                    }
                    break;
                }
                cursor = Instant.ofEpochMilli(t);
            }
        }
        if (earliestRule != null) {
            String reason = earliestRule.label().isBlank() ? "Geplanter Neustart" : earliestRule.label();
            ActiveCountdown next = new ActiveCountdown(earliestRule.id(), Source.SCHEDULED, earliestRule.id(),
                    earliestTime, "Zeitplan:" + reason, reason, earliestRule.requirePending());
            armInternal(next, nowMillis, false);
            log.info("Geplanter Neustart aktiv ('" + reason + "', verbleibend " + formatDuration(next.remainingSeconds(nowMillis)) + ").");
        }
    }

    private void armInternal(ActiveCountdown next, long nowMillis, boolean resuming) {
        this.active = next;
        this.firedOrSkippedWarnings.clear();
        long remSec = next.remainingSeconds(nowMillis);
        for (Integer t : config.restartWarningSeconds()) {
            if (t > remSec || (resuming && t >= remSec)) {
                firedOrSkippedWarnings.add(t);
            }
        }
    }

    private List<Integer> collectDueWarnings(int remSec) {
        List<Integer> cfg = new java.util.ArrayList<>(config.restartWarningSeconds());
        cfg.sort(Collections.reverseOrder());
        List<Integer> fire = new java.util.ArrayList<>();
        for (int threshold : cfg) {
            if (threshold > 0 && remSec <= threshold && firedOrSkippedWarnings.add(threshold)) {
                fire.add(threshold);
            }
        }
        return fire;
    }

    private CompletableFuture<RestartService.Outcome> executeNow(String actor, boolean requirePending, String reason) {
        RestartService rs = restartService;
        if (rs == null) {
            executing.set(false);
            String msg = "Neustart-Service ist nicht initialisiert";
            log.error(msg, null);
            record("restart-scheduled", "FAILED", actor, msg);
            listener.onExecutionFailed(actor, msg);
            return CompletableFuture.completedFuture(new RestartService.Outcome(RestartService.Result.FAILED, List.of(msg)));
        }
        RestartService.Readiness readiness = rs.readiness();
        if (!readiness.ready()) {
            executing.set(false);
            String msg = "Neustart abgebrochen (" + reason + "): " + readiness.detail();
            log.error(msg, null);
            record("restart-scheduled", "FAILED", actor, msg);
            listener.onExecutionFailed(actor, readiness.detail());
            return CompletableFuture.completedFuture(new RestartService.Outcome(RestartService.Result.FAILED, List.of(readiness.detail())));
        }
        return rs.restart(actor, requirePending).whenComplete((out, err) -> {
            executing.set(false);
            if (err != null) {
                String msg = log.redact(String.valueOf(err.getMessage()));
                log.error("Geplanter/einmaliger Neustart fehlgeschlagen: " + msg, err);
                record("restart-scheduled", "FAILED", actor, msg);
                listener.onExecutionFailed(actor, msg);
                return;
            }
            if (out.result() != RestartService.Result.REQUESTED && out.result() != RestartService.Result.REQUESTED_LOCAL) {
                String msg = out.details().isEmpty() ? out.result().name() : String.join("; ", out.details());
                log.warn("Neustart nicht ausgefuehrt (" + out.result() + "): " + msg);
                record("restart-scheduled", "FAILED", actor, out.result() + ": " + msg);
                listener.onExecutionFailed(actor, msg);
            }
        });
    }

    private static String slotKey(String ruleId, long targetMillis) {
        return ruleId + "@" + targetMillis;
    }

    private void record(String action, String result, String actor, String msg) {
        HistoryStore h = history;
        if (h != null) h.append(HistoryEntry.of(action, result, actor, msg));
    }

    public static String formatDuration(long totalSeconds) {
        long s = Math.max(0L, totalSeconds);
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long rem = s % 60L;
        if (h > 0) {
            return m == 0 && rem == 0 ? h + " Std." : String.format("%d Std. %02d Min. %02d Sek.", h, m, rem);
        }
        if (m > 0) {
            return rem == 0 ? (m == 1 ? "1 Minute" : m + " Minuten") : m + " Min. " + rem + " Sek.";
        }
        return rem == 1 ? "1 Sekunde" : rem + " Sekunden";
    }
}
