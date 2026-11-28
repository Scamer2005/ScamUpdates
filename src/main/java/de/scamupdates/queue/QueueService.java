package de.scamupdates.queue;

import de.scamupdates.config.ConfigManager;
import de.scamupdates.history.HistoryEntry;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.match.MatchService;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.model.UpdateCandidate;
import de.scamupdates.update.UpdateService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wertet die persistente Update-Warteschlange gegen aktuelle Update-Snapshots und mehrdeutige Treffer aus
 * und steuert "Jetzt aktualisieren", "Spaeter" sowie "Nicht mehr erinnern" (ohne Bukkit-Abhaengigkeit).
 */
public final class QueueService {

    public enum EntryState {
        READY,
        AMBIGUOUS,
        WAITING,
        SNOOZED,
        EXCLUDED,
        PENDING_RESTART,
        INCOMPATIBLE,
        UNMATCHED,
        ERROR,
        MISSING_PLUGIN
    }

    public record EvaluatedEntry(UpdateQueueStore.Entry entry, EntryState state,
                                 UpdateService.PluginStatus pluginStatus, UpdateCandidate candidate,
                                 List<ProjectRef> candidates, String detail) {}

    private final UpdateQueueStore store;
    private final ConfigManager config;
    private final ScamLog log;
    private volatile HistoryStore history;

    public QueueService(UpdateQueueStore store, ConfigManager config, ScamLog log) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
    }

    public void setHistory(HistoryStore history) { this.history = history; }

    public UpdateQueueStore store() { return store; }

    public List<EvaluatedEntry> evaluate(UpdateService.Snapshot snapshot, MatchService matches, Set<String> installedPlugins) {
        return evaluate(snapshot, matches, installedPlugins, System.currentTimeMillis());
    }

    /** Wertet alle Eintraege der Warteschlange anhand des aktuellen Snapshots und der offenen Treffer aus. */
    public List<EvaluatedEntry> evaluate(UpdateService.Snapshot snapshot, MatchService matches,
                                         Set<String> installedPlugins, long nowMillis) {
        List<UpdateQueueStore.Entry> entries = store.all();
        if (entries.isEmpty()) return List.of();

        Map<String, UpdateService.PluginStatus> byPlugin = new LinkedHashMap<>();
        if (snapshot != null) {
            for (UpdateService.PluginStatus st : snapshot.statuses()) {
                byPlugin.put(st.plugin().name().toLowerCase(Locale.ROOT), st);
            }
        }
        Set<String> installedLower = installedPlugins == null
                ? Set.of()
                : installedPlugins.stream().filter(Objects::nonNull).map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());

        List<EvaluatedEntry> out = new ArrayList<>();
        for (UpdateQueueStore.Entry e : entries) {
            String key = e.plugin().toLowerCase(Locale.ROOT);
            if (!installedLower.isEmpty() && !installedLower.contains(key)) {
                store.updateObservation(e.plugin(), e.installedVersion(), e.targetVersion(), e.sourceId(), EntryState.MISSING_PLUGIN.name());
                out.add(new EvaluatedEntry(store.get(e.plugin()).orElse(e), EntryState.MISSING_PLUGIN,
                        null, null, List.of(), "Plugin ist nicht mehr installiert"));
                continue;
            }

            UpdateService.PluginStatus st = byPlugin.get(key);
            if (st == null) {
                EntryState state = e.snoozedUntil() > nowMillis ? EntryState.SNOOZED : EntryState.WAITING;
                String msg = snapshot == null || snapshot.time() == 0
                        ? "Warte auf Update-Pruefung"
                        : "Warte auf verfuegbares Update";
                out.add(new EvaluatedEntry(e, state, null, null, List.of(), msg));
                continue;
            }

            out.add(evaluateWithStatus(e, st, matches, nowMillis));
        }
        return out;
    }

    private EvaluatedEntry evaluateWithStatus(UpdateQueueStore.Entry e, UpdateService.PluginStatus st,
                                              MatchService matches, long nowMillis) {
        String installed = st.plugin().version();
        String source = st.project() == null ? e.sourceId() : st.project().sourceId();
        String target = st.release() == null ? e.targetVersion() : st.release().version();

        switch (st.kind()) {
            case UPDATE_AVAILABLE -> {
                List<ProjectRef> choiceList = matches == null ? List.of()
                        : matches.pendingFor(st.plugin().name()).map(MatchService.PendingChoice::candidates).orElse(List.of());
                if (!choiceList.isEmpty()) {
                    store.updateObservation(e.plugin(), installed, target, source, EntryState.AMBIGUOUS.name());
                    return new EvaluatedEntry(store.get(e.plugin()).orElse(e), EntryState.AMBIGUOUS,
                            st, null, choiceList, "Mehrere Kandidaten gefunden (" + choiceList.size() + ") - Auswahl noetig");
                }
                UpdateCandidate cand = new UpdateCandidate(st.plugin(), st.project(), st.release());
                boolean snoozed = e.snoozedUntil() > nowMillis;
                EntryState state = snoozed ? EntryState.SNOOZED : EntryState.READY;
                store.updateObservation(e.plugin(), installed, target, source, state.name());
                String detail = st.release().downloadUrl() == null
                        ? "Update " + target + " verfuegbar (kein Direktdownload)"
                        : (snoozed ? "Update " + target + " verfuegbar (zurueckgestellt)" : "Update " + target + " bereit");
                return new EvaluatedEntry(store.get(e.plugin()).orElse(e), state, st, cand, List.of(), detail);
            }
            case PENDING_MATCH -> {
                List<ProjectRef> choiceList = matches == null ? List.of()
                        : matches.pendingFor(st.plugin().name()).map(MatchService.PendingChoice::candidates).orElse(List.of());
                store.updateObservation(e.plugin(), installed, "", source, EntryState.AMBIGUOUS.name());
                return new EvaluatedEntry(store.get(e.plugin()).orElse(e), EntryState.AMBIGUOUS,
                        st, null, choiceList, "Mehrere Treffer gefunden - bitte Quelle waehlen");
            }
            case PENDING_RESTART -> {
                store.updateObservation(e.plugin(), installed, target, source, EntryState.PENDING_RESTART.name());
                return new EvaluatedEntry(store.get(e.plugin()).orElse(e), EntryState.PENDING_RESTART,
                        st, null, List.of(), "Update bereits geladen, Neustart noetig");
            }
            case EXCLUDED -> {
                store.updateObservation(e.plugin(), installed, "", source, EntryState.EXCLUDED.name());
                return new EvaluatedEntry(store.get(e.plugin()).orElse(e), EntryState.EXCLUDED,
                        st, null, List.of(), "Plugin ist ausgeschlossen");
            }
            case INCOMPATIBLE -> {
                store.updateObservation(e.plugin(), installed, target, source, EntryState.INCOMPATIBLE.name());
                return new EvaluatedEntry(store.get(e.plugin()).orElse(e), EntryState.INCOMPATIBLE,
                        st, null, List.of(), "Version " + target + " passt nicht zur Minecraft-Version");
            }
            case UNMATCHED -> {
                store.updateObservation(e.plugin(), installed, "", source, EntryState.UNMATCHED.name());
                return new EvaluatedEntry(store.get(e.plugin()).orElse(e), EntryState.UNMATCHED,
                        st, null, List.of(), "Noch keine passende Update-Quelle gefunden");
            }
            case ERROR -> {
                store.updateObservation(e.plugin(), installed, target, source, EntryState.ERROR.name());
                return new EvaluatedEntry(store.get(e.plugin()).orElse(e), EntryState.ERROR,
                        st, null, List.of(), st.error() == null ? "Prueffehler" : st.error());
            }
            case UP_TO_DATE, NO_RELEASE -> {
                store.updateObservation(e.plugin(), installed, "", source, EntryState.WAITING.name());
                return new EvaluatedEntry(store.get(e.plugin()).orElse(e), EntryState.WAITING,
                        st, null, List.of(), "Aktuell (" + installed + ") - warte auf neues Update");
            }
        }
        return new EvaluatedEntry(e, EntryState.WAITING, st, null, List.of(), "Wartend");
    }

    public List<EvaluatedEntry> pendingNotifications(UpdateService.Snapshot snapshot, MatchService matches,
                                                     Set<String> installedPlugins, long nowMillis) {
        List<EvaluatedEntry> out = new ArrayList<>();
        for (EvaluatedEntry ev : evaluate(snapshot, matches, installedPlugins, nowMillis)) {
            if (ev.state() == EntryState.READY && ev.candidate() != null) {
                if (!ev.candidate().toVersion().equals(ev.entry().notifiedVersion())) {
                    out.add(ev);
                }
            } else if (ev.state() == EntryState.AMBIGUOUS && ev.entry().snoozedUntil() <= nowMillis) {
                if (!"AMBIGUOUS".equals(ev.entry().notifiedVersion())) {
                    out.add(ev);
                }
            }
        }
        return out;
    }

    public boolean markNotified(EvaluatedEntry ev) {
        if (ev == null) return false;
        if (ev.state() == EntryState.READY && ev.candidate() != null) {
            return store.markNotified(ev.entry().plugin(), ev.candidate().toVersion());
        }
        if (ev.state() == EntryState.AMBIGUOUS) {
            return store.markNotified(ev.entry().plugin(), "AMBIGUOUS");
        }
        return false;
    }

    /** "Spaeter": Eintrag bleibt dauerhaft erhalten, erneute Erinnerung wird pausiert. */
    public boolean snooze(String plugin, String actor) {
        return snooze(plugin, actor, System.currentTimeMillis());
    }

    public boolean snooze(String plugin, String actor, long nowMillis) {
        long delayMs = Math.max(1, config.queueSnoozeMinutes()) * 60_000L;
        boolean ok = store.snooze(plugin, nowMillis + delayMs);
        if (ok) {
            log.info("Warteschlange (" + plugin + "): von " + actor + " auf spaeter verschoben.");
            record("queue-later", "INFO", actor, "Update-Erinnerung fuer " + plugin + " auf spaeter verschoben");
        }
        return ok;
    }

    /** "Nicht mehr erinnern": entfernt den Warteschlangen-Eintrag dauerhaft. */
    public boolean dismiss(String plugin, String actor) {
        boolean ok = store.dismiss(plugin);
        if (ok) {
            log.info("Warteschlange (" + plugin + "): von " + actor + " dauerhaft entfernt ('Nicht mehr erinnern').");
            record("queue-dismiss", "INFO", actor, plugin + " aus Update-Warteschlange entfernt");
        }
        return ok;
    }

    /** "Keins davon" bei mehrdeutigen Kandidaten: schliesst Plugin ueber MatchService aus und startet KEIN Update. */
    public MatchService.PickResult chooseNone(String plugin, MatchService matches, String actor) {
        Optional<MatchService.PendingChoice> pc = matches.pendingFor(plugin);
        if (pc.isPresent()) {
            String canonical = pc.get().plugin();
            MatchService.PickResult r = matches.pick(canonical, 0);
            if (r == MatchService.PickResult.EXCLUDED) {
                store.updateObservation(canonical, "", "", "", EntryState.EXCLUDED.name());
                record("queue-pick-none", "INFO", actor, "'Keins davon' fuer " + canonical + " gewaehlt (kein Update gestartet)");
            }
            return r;
        }
        return MatchService.PickResult.NOT_PENDING;
    }

    private void record(String action, String result, String actor, String message) {
        HistoryStore h = history;
        if (h != null) h.append(HistoryEntry.of(action, result, actor, message));
    }
}
