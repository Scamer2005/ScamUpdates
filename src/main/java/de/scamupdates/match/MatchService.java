package de.scamupdates.match;

import de.scamupdates.config.ConfigManager;
import de.scamupdates.config.ManualMapping;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.sources.NameMatcher;
import de.scamupdates.sources.SourceRegistry;
import de.scamupdates.sources.UpdateSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ordnet Plugins Projekten zu. Bei mehreren Treffern wird NIE automatisch gewaehlt: der Admin entscheidet
 * ueber /scamupdates match bzw. pick; "Keins davon" wird dauerhaft als Ausschluss gespeichert.
 */
public final class MatchService {

    public enum Type { MANUAL, ASSIGNED, EXCLUDED, PENDING, UNMATCHED }

    public record Resolution(Type type, ProjectRef project, List<ProjectRef> candidates) {}

    public record PendingChoice(String plugin, List<ProjectRef> candidates) {}

    public enum PickResult { ASSIGNED, EXCLUDED, NOT_PENDING, BAD_INDEX, STORAGE_ERROR }

    private static final Pattern GITHUB = Pattern.compile("github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)");
    private static final Pattern MODRINTH = Pattern.compile("modrinth\\.com/(?:plugin|mod|project)/([A-Za-z0-9_-]+)");
    private static final Pattern HANGAR = Pattern.compile("hangar\\.papermc\\.io/([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)");
    private static final Pattern SPIGOT = Pattern.compile("spigotmc\\.org/resources/(?:[^/\\s]*\\.)?(\\d+)");

    private final ConfigManager config;
    private final SourceRegistry registry;
    private final MatchStore store;
    private final ScamLog log;
    private final Map<String, PendingChoice> pending = new ConcurrentHashMap<>();

    public MatchService(ConfigManager config, SourceRegistry registry, MatchStore store, ScamLog log) {
        this.config = config;
        this.registry = registry;
        this.store = store;
        this.log = log;
    }

    public MatchStore store() { return store; }

    public List<PendingChoice> pendingChoices() {
        return new ArrayList<>(pending.values());
    }

    public Optional<PendingChoice> pendingFor(String plugin) {
        return Optional.ofNullable(pending.get(plugin.toLowerCase(Locale.ROOT)));
    }

    public void clearPending() { pending.clear(); }

    public Resolution resolve(PluginInfo p) throws InterruptedException {
        Optional<ManualMapping> mm = config.manualMapping(p.name());
        if (mm.isPresent()) {
            if (!mm.get().enabled()) return new Resolution(Type.EXCLUDED, null, List.of());
            Optional<ProjectRef> ref = mm.get().toProjectRef();
            if (ref.isPresent()) {
                pending.remove(p.name().toLowerCase(Locale.ROOT));
                return new Resolution(Type.MANUAL, ref.get(), List.of());
            }
        }
        if (store.exclusion(p.name()).isPresent()) return new Resolution(Type.EXCLUDED, null, List.of());
        Optional<ProjectRef> assigned = store.assignment(p.name());
        if (assigned.isPresent()) return new Resolution(Type.ASSIGNED, assigned.get(), List.of());

        PendingChoice existing = pending.get(p.name().toLowerCase(Locale.ROOT));
        if (existing != null) return new Resolution(Type.PENDING, null, existing.candidates());
        if (!store.shouldSearch(p.name(), config.retryUnmatchedHours())) return new Resolution(Type.UNMATCHED, null, List.of());

        List<ProjectRef> candidates = findCandidates(p);
        try {
            if (candidates.isEmpty()) {
                store.markUnmatched(p.name());
                log.info("Keine Quelle gefunden fuer Plugin " + p.name());
                return new Resolution(Type.UNMATCHED, null, List.of());
            }
            if (candidates.size() == 1 && config.autoAssignSingle()) {
                store.assign(p.name(), candidates.get(0));
                log.info("Plugin " + p.name() + " automatisch zugeordnet: " + candidates.get(0).key());
                return new Resolution(Type.ASSIGNED, candidates.get(0), List.of());
            }
        } catch (IOException e) {
            log.error("matches.yml konnte nicht gespeichert werden", e);
        }
        pending.put(p.name().toLowerCase(Locale.ROOT), new PendingChoice(p.name(), candidates));
        log.info("Mehrere/unbestaetigte Treffer fuer " + p.name() + " (" + candidates.size() + "): Admin-Auswahl noetig.");
        return new Resolution(Type.PENDING, null, candidates);
    }

    private List<ProjectRef> findCandidates(PluginInfo p) throws InterruptedException {
        Map<String, ProjectRef> found = new LinkedHashMap<>();
        for (ProjectRef r : hints(p)) found.putIfAbsent(r.key(), r);
        for (UpdateSource src : registry.all()) {
            if (!registry.enabled(src.id()) || !src.supportsSearch()) continue;
            try {
                for (ProjectRef r : src.search(p)) {
                    if (NameMatcher.same(r.name(), p.name())) found.putIfAbsent(r.key(), r);
                }
            } catch (IOException e) {
                log.warn("Suche bei " + src.displayName() + " fuer " + p.name() + " fehlgeschlagen: " + e.getMessage());
            }
            Thread.sleep(Math.max(0, config.requestDelayMs()));
        }
        return new ArrayList<>(found.values());
    }

    /** Projekte, auf die das Plugin selbst (Website/Beschreibung) verweist. */
    List<ProjectRef> hints(PluginInfo p) {
        String text = (p.website() == null ? "" : p.website()) + " " + (p.description() == null ? "" : p.description());
        List<ProjectRef> out = new ArrayList<>();
        if (registry.enabled("github")) {
            Matcher m = GITHUB.matcher(text);
            while (m.find()) {
                String repo = m.group(2).replaceAll("\\.git$", "").replaceAll("\\.+$", "");
                if (m.group(1).equals("sponsors") || repo.isEmpty()) continue;
                out.add(new ProjectRef("github", m.group(1) + "/" + repo, repo, "https://github.com/" + m.group(1) + "/" + repo));
            }
        }
        if (registry.enabled("modrinth")) {
            Matcher m = MODRINTH.matcher(text);
            while (m.find()) out.add(new ProjectRef("modrinth", m.group(1), m.group(1), "https://modrinth.com/project/" + m.group(1)));
        }
        if (registry.enabled("hangar")) {
            Matcher m = HANGAR.matcher(text);
            while (m.find()) out.add(new ProjectRef("hangar", m.group(2), m.group(2), "https://hangar.papermc.io/" + m.group(1) + "/" + m.group(2)));
        }
        if (registry.enabled("spigot")) {
            Matcher m = SPIGOT.matcher(text);
            while (m.find()) out.add(new ProjectRef("spigot", m.group(1), p.name(), "https://www.spigotmc.org/resources/" + m.group(1)));
        }
        return out;
    }

    /** index 0 = "Keins davon", 1..n = Kandidat. */
    public PickResult pick(String plugin, int index) {
        PendingChoice pc = pending.get(plugin.toLowerCase(Locale.ROOT));
        if (pc == null) return PickResult.NOT_PENDING;
        if (index < 0 || index > pc.candidates().size()) return PickResult.BAD_INDEX;
        try {
            if (index == 0) {
                store.exclude(pc.plugin(), MatchStore.Reason.NONE_OF_THESE);
                log.info("Admin-Auswahl: 'Keins davon' fuer " + pc.plugin() + " (dauerhaft ausgeschlossen)");
                pending.remove(plugin.toLowerCase(Locale.ROOT));
                return PickResult.EXCLUDED;
            }
            ProjectRef ref = pc.candidates().get(index - 1);
            store.assign(pc.plugin(), ref);
            log.info("Admin-Auswahl: " + pc.plugin() + " -> " + ref.key());
            pending.remove(plugin.toLowerCase(Locale.ROOT));
            return PickResult.ASSIGNED;
        } catch (IOException e) {
            log.error("Auswahl konnte nicht gespeichert werden", e);
            return PickResult.STORAGE_ERROR;
        }
    }
}
