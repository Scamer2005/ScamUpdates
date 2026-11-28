package de.scamupdates.gui;

import de.scamupdates.backup.BackupManager;
import de.scamupdates.match.MatchStore;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.scan.PluginScanner;
import de.scamupdates.security.Perm;
import de.scamupdates.sources.McVersionMatcher;
import de.scamupdates.update.UpdateService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static de.scamupdates.messaging.Messages.ph;

/**
 * Plugin-Bibliothek mit allen installierten Plugins, Statusfilter, Sortierung, Anvil-Suche
 * und Seitenwechsel. Klick auf ein Plugin oeffnet die Detailansicht.
 */
final class PluginLibraryMenu extends PagedMenu<PluginLibraryMenu.Row> {

    enum Filter {
        ALL, UPDATES, CURRENT, PROBLEMS, EXCLUDED, WITH_BACKUP;

        Filter next() { return values()[(ordinal() + 1) % values().length]; }

        String labelKey() {
            return switch (this) {
                case ALL -> "lib-filter-all";
                case UPDATES -> "lib-filter-updates";
                case CURRENT -> "lib-filter-current";
                case PROBLEMS -> "lib-filter-problems";
                case EXCLUDED -> "lib-filter-excluded";
                case WITH_BACKUP -> "lib-filter-backup";
            };
        }
    }

    enum Sort {
        NAME, STATUS, UPDATES_FIRST, BACKUP_FIRST;

        Sort next() { return values()[(ordinal() + 1) % values().length]; }

        String labelKey() {
            return switch (this) {
                case NAME -> "lib-sort-name";
                case STATUS -> "lib-sort-status";
                case UPDATES_FIRST -> "lib-sort-updates";
                case BACKUP_FIRST -> "lib-sort-backup";
            };
        }
    }

    record Row(PluginInfo plugin, UpdateService.PluginStatus status, UpdateService.Kind kind,
               String latestVersion, String source, String statusLabel, String compatLabel,
               String backupLabel, boolean hasBackup, boolean excluded, boolean queued) {}

    private long lastSnapshotTime = -1L;

    PluginLibraryMenu(GuiService gui, Player viewer) { super(gui, viewer); }

    @Override protected int rows() { return 6; }

    @Override protected Component title() { return gui.config().title("library", pageResolvers()); }

    @Override
    protected List<Row> entries() {
        UpdateService us = gui.plugin().updates();
        UpdateService.Snapshot snap = us == null ? new UpdateService.Snapshot(0, List.of()) : us.snapshot();
        lastSnapshotTime = snap.time();

        Map<String, UpdateService.PluginStatus> byName = new LinkedHashMap<>();
        for (UpdateService.PluginStatus st : snap.statuses()) {
            byName.put(st.plugin().name().toLowerCase(Locale.ROOT), st);
        }

        List<Row> raw = new ArrayList<>();
        String mc = Bukkit.getMinecraftVersion();
        for (PluginInfo p : PluginScanner.collectMetadata()) {
            String key = p.name().toLowerCase(Locale.ROOT);
            UpdateService.PluginStatus st = byName.get(key);
            Optional<BackupManager.Entry> bk = gui.plugin().backups().get(p.name());
            boolean hasBackup = bk.isPresent();
            String backupLabel = bk.map(e -> "v" + e.version()).orElse("keines");
            boolean excluded = gui.plugin().matches().store().exclusion(p.name()).isPresent()
                    || (st != null && st.kind() == UpdateService.Kind.EXCLUDED);
            boolean queued = gui.plugin().queue() != null && gui.plugin().queue().contains(p.name());
            boolean pendingRestart = gui.plugin().transactions().get(p.name()).isPresent();
            boolean pendingMatch = gui.plugin().matches().pendingFor(p.name()).isPresent();

            UpdateService.Kind kind;
            if (pendingRestart) kind = UpdateService.Kind.PENDING_RESTART;
            else if (pendingMatch) kind = UpdateService.Kind.PENDING_MATCH;
            else if (excluded) kind = UpdateService.Kind.EXCLUDED;
            else if (st != null) kind = st.kind();
            else kind = null;

            String latest = st != null && st.release() != null ? st.release().version() : "-";
            String source = st != null && st.project() != null ? st.project().sourceId()
                    : gui.plugin().matches().store().assignment(p.name()).map(a -> a.sourceId()).orElse("-");
            String statusText = statusText(kind, st);
            String compatText = compatText(kind, st, mc);
            raw.add(new Row(p, st, kind, latest, source, statusText, compatText, backupLabel, hasBackup, excluded, queued));
        }
        return filterAndSort(raw, gui.libraryFilter(viewer), gui.librarySort(viewer), gui.libraryQuery(viewer));
    }

    static List<Row> filterAndSort(List<Row> raw, Filter filter, Sort sort, String query) {
        List<Row> out = new ArrayList<>();
        for (Row r : raw) {
            if (!matchesFilter(r, filter)) continue;
            if (!matchesQuery(r, query)) continue;
            out.add(r);
        }
        out.sort(comparator(sort));
        return out;
    }

    static boolean matchesFilter(Row r, Filter filter) {
        if (filter == null || filter == Filter.ALL) return true;
        return switch (filter) {
            case ALL -> true;
            case UPDATES -> r.kind() == UpdateService.Kind.UPDATE_AVAILABLE;
            case CURRENT -> r.kind() == UpdateService.Kind.UP_TO_DATE || r.kind() == UpdateService.Kind.NO_RELEASE;
            case PROBLEMS -> r.kind() == UpdateService.Kind.ERROR || r.kind() == UpdateService.Kind.UNMATCHED
                    || r.kind() == UpdateService.Kind.INCOMPATIBLE || r.kind() == UpdateService.Kind.PENDING_MATCH;
            case EXCLUDED -> r.excluded() || r.kind() == UpdateService.Kind.EXCLUDED;
            case WITH_BACKUP -> r.hasBackup();
        };
    }

    static boolean matchesQuery(Row r, String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        return r.plugin().name().toLowerCase(Locale.ROOT).contains(q)
                || r.source().toLowerCase(Locale.ROOT).contains(q)
                || r.statusLabel().toLowerCase(Locale.ROOT).contains(q);
    }

    static Comparator<Row> comparator(Sort sort) {
        Comparator<Row> byName = Comparator.comparing(r -> r.plugin().name().toLowerCase(Locale.ROOT));
        if (sort == null || sort == Sort.NAME) return byName;
        return switch (sort) {
            case NAME -> byName;
            case STATUS -> Comparator.comparing((Row r) -> r.statusLabel().toLowerCase(Locale.ROOT)).thenComparing(byName);
            case UPDATES_FIRST -> Comparator.comparingInt((Row r) -> priority(r.kind())).thenComparing(byName);
            case BACKUP_FIRST -> Comparator.comparing((Row r) -> !r.hasBackup()).thenComparing(byName);
        };
    }

    private static int priority(UpdateService.Kind k) {
        if (k == null) return 6;
        return switch (k) {
            case UPDATE_AVAILABLE -> 0;
            case PENDING_MATCH -> 1;
            case PENDING_RESTART -> 2;
            case ERROR, UNMATCHED, INCOMPATIBLE -> 3;
            case UP_TO_DATE, NO_RELEASE -> 4;
            case EXCLUDED -> 5;
        };
    }

    private String statusText(UpdateService.Kind kind, UpdateService.PluginStatus st) {
        if (kind == null) return "noch nicht geprueft";
        return switch (kind) {
            case UPDATE_AVAILABLE -> st != null && st.release() != null && st.release().downloadUrl() == null
                    ? "Update (kein Direktdownload)" : "Update verfuegbar";
            case UP_TO_DATE, NO_RELEASE -> "Aktuell";
            case PENDING_MATCH -> "Zuordnung offen";
            case PENDING_RESTART -> "Vorgemerkt (Neustart)";
            case EXCLUDED -> "Ausgeschlossen";
            case INCOMPATIBLE -> "Inkompatibel";
            case UNMATCHED -> "Keine Quelle";
            case ERROR -> "Fehler";
        };
    }

    private String compatText(UpdateService.Kind kind, UpdateService.PluginStatus st, String mc) {
        if (kind == UpdateService.Kind.INCOMPATIBLE) return "nicht kompatibel (" + mc + ")";
        if (st != null && st.release() != null && !st.release().gameVersions().isEmpty()) {
            return McVersionMatcher.anyMatches(st.release().gameVersions(), mc)
                    ? "kompatibel (" + mc + ")" : "abweichende MC-Version";
        }
        return gui.compatLabel();
    }

    @Override
    protected Button button(Row r) {
        String id;
        Material fallback;
        if (r.kind() == null) {
            id = "lib-current";
            fallback = Material.LIGHT_BLUE_DYE;
        } else {
            switch (r.kind()) {
                case UPDATE_AVAILABLE -> { id = "lib-update"; fallback = Material.LIME_DYE; }
                case PENDING_MATCH -> { id = "lib-open"; fallback = Material.YELLOW_DYE; }
                case PENDING_RESTART -> { id = "lib-pending"; fallback = Material.CYAN_DYE; }
                case ERROR, UNMATCHED, INCOMPATIBLE -> { id = "lib-problem"; fallback = Material.RED_DYE; }
                case EXCLUDED -> { id = "lib-excluded"; fallback = Material.GRAY_DYE; }
                default -> { id = "lib-current"; fallback = Material.LIGHT_BLUE_DYE; }
            }
        }
        ItemBuilder b = gui.item(id, fallback,
                ph("plugin", r.plugin().name()),
                ph("installed", r.plugin().version()),
                ph("latest", r.latestVersion()),
                ph("source", r.source()),
                ph("status", r.statusLabel() + (r.queued() ? " [Warteschlange]" : "")),
                ph("compat", r.compatLabel()),
                ph("backup", r.backupLabel()));
        return Button.of(b.build(), Perm.UPDATE_SINGLE, c -> gui.openDetail(viewer, r.plugin().name()));
    }

    @Override
    protected Button emptyButton() {
        return Button.display(gui.item("lib-empty", Material.STRUCTURE_VOID).build());
    }

    @Override
    protected void extras() {
        Filter curFilter = gui.libraryFilter(viewer);
        Sort curSort = gui.librarySort(viewer);
        String query = gui.libraryQuery(viewer);

        set(Slots.EXTRA_LEFT, Button.of(gui.item("lib-filter", Material.HOPPER,
                ph("filter", gui.config().label(curFilter.labelKey()))).build(), Perm.UPDATE_SINGLE, c -> {
            gui.setLibraryFilter(viewer, curFilter.next());
            goTo(0);
        }));

        set(Slots.EXTRA_RIGHT, Button.of(gui.item("lib-sort", Material.COMPARATOR,
                ph("sort", gui.config().label(curSort.labelKey()))).build(), Perm.UPDATE_SINGLE, c -> {
            gui.setLibrarySort(viewer, curSort.next());
            goTo(0);
        }));

        set(Slots.CONFIRM, Button.of(gui.item("lib-search", Material.NAME_TAG,
                ph("query", query.isEmpty() ? "alle" : query)).build(), Perm.UPDATE_SINGLE, c -> {
            if (c.isRight()) {
                gui.setLibraryQuery(viewer, "");
                goTo(0);
                return;
            }
            String label = gui.plugin().messages().plain(gui.config().text("text.library.search-prompt"));
            String initial = query.isEmpty() ? "-" : query;
            gui.ask(viewer, gui.simplePrompt(label, initial,
                    in -> Optional.empty(),
                    in -> {
                        String clean = in == null || in.equals("-") ? "" : in.trim();
                        gui.setLibraryQuery(viewer, clean);
                        page = 0;
                        open();
                    },
                    () -> open()));
        }));
    }

    @Override
    protected boolean needsRender() {
        UpdateService us = gui.plugin().updates();
        return us != null && us.snapshot().time() != lastSnapshotTime;
    }

    @Override protected void back() { gui.openMain(viewer); }
}
