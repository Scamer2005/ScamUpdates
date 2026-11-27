package de.scamupdates.gui;

import de.scamupdates.match.MatchService;
import de.scamupdates.security.Perm;
import de.scamupdates.update.UpdateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static de.scamupdates.messaging.Messages.ph;

/** Ergebnisliste des Update-Checks (nur Ansicht). Farben: Lime = Update, Hellblau = aktuell, Gelb = Klaerung, Rot = Problem, Grau = ausgeschlossen. */
final class ResultsMenu extends PagedMenu<UpdateService.PluginStatus> {

    enum Filter {
        ALL, UPDATES, PROBLEMS;

        Filter next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private final Filter filter;

    ResultsMenu(GuiService gui, Player viewer, Filter filter) {
        super(gui, viewer);
        this.filter = filter;
    }

    @Override protected int rows() { return 6; }

    @Override protected Component title() { return gui.config().title("results", pageResolvers()); }

    @Override
    protected List<UpdateService.PluginStatus> entries() {
        UpdateService us = gui.plugin().updates();
        if (us == null) return List.of();
        List<UpdateService.PluginStatus> out = new ArrayList<>();
        for (UpdateService.PluginStatus s : us.snapshot().statuses()) {
            if (matches(s.kind())) out.add(s);
        }
        out.sort(Comparator.comparingInt((UpdateService.PluginStatus s) -> priority(s.kind()))
                .thenComparing(s -> s.plugin().name().toLowerCase(Locale.ROOT)));
        return out;
    }

    private boolean matches(UpdateService.Kind k) {
        return switch (filter) {
            case ALL -> true;
            case UPDATES -> k == UpdateService.Kind.UPDATE_AVAILABLE;
            case PROBLEMS -> k == UpdateService.Kind.ERROR || k == UpdateService.Kind.UNMATCHED
                    || k == UpdateService.Kind.INCOMPATIBLE || k == UpdateService.Kind.PENDING_MATCH;
        };
    }

    private static int priority(UpdateService.Kind k) {
        return switch (k) {
            case UPDATE_AVAILABLE -> 0;
            case PENDING_MATCH -> 1;
            case ERROR, UNMATCHED, INCOMPATIBLE -> 2;
            case PENDING_RESTART -> 3;
            case UP_TO_DATE, NO_RELEASE -> 4;
            case EXCLUDED -> 5;
        };
    }

    @Override
    protected Button button(UpdateService.PluginStatus s) {
        boolean detailed = gui.detailed(viewer);
        String id;
        Material fallback;
        switch (s.kind()) {
            case UPDATE_AVAILABLE -> { id = "results-update"; fallback = Material.LIME_DYE; }
            case UP_TO_DATE, NO_RELEASE -> { id = "results-current"; fallback = Material.LIGHT_BLUE_DYE; }
            case PENDING_MATCH -> { id = "results-open"; fallback = Material.YELLOW_DYE; }
            case ERROR, UNMATCHED, INCOMPATIBLE -> { id = "results-problem"; fallback = Material.RED_DYE; }
            default -> { id = "results-excluded"; fallback = Material.GRAY_DYE; }
        }
        String source = s.project() == null ? "-" : s.project().sourceId();
        ItemBuilder b = gui.item(id, fallback, ph("plugin", s.plugin().name()), ph("version", s.plugin().version()),
                ph("target", s.release() == null ? "-" : s.release().version()), ph("source", source),
                ph("channel", s.release() == null ? "-" : s.release().channel().name().toLowerCase(Locale.ROOT)),
                ph("info", label(s)));
        if (detailed) b.addLore(details(s));
        return Button.display(b.build());
    }

    private static String label(UpdateService.PluginStatus s) {
        return switch (s.kind()) {
            case UPDATE_AVAILABLE -> s.release() != null && s.release().downloadUrl() == null
                    ? "Update verfuegbar (kein Direktdownload)" : "Update verfuegbar";
            case UP_TO_DATE -> "Aktuell";
            case NO_RELEASE -> "Keine passende Version bei der Quelle";
            case EXCLUDED -> "Ausgeschlossen";
            case UNMATCHED -> "Keine Quelle gefunden";
            case PENDING_MATCH -> "Mehrere Treffer: /scamupdates match";
            case PENDING_RESTART -> "Vorgemerkt, Neustart noetig";
            case INCOMPATIBLE -> "Update passt nicht zur Minecraft-Version";
            case ERROR -> "Fehler";
        };
    }

    /** Zusatzzeilen der detaillierten Ansicht: Quelle, Projekt, Kandidaten, Fehlerursache. */
    private List<Component> details(UpdateService.PluginStatus s) {
        List<Component> l = new ArrayList<>();
        l.add(Component.empty());
        if (s.project() != null) {
            l.add(line("Projekt: " + s.project().sourceId() + " / " + s.project().projectId()));
            if (!s.project().url().isBlank()) l.add(line("URL: " + s.project().url()));
        }
        if (s.release() != null) {
            l.add(line("Neueste Version: " + s.release().version() + " (" + s.release().channel().name().toLowerCase(Locale.ROOT) + ")"));
            if (!s.release().gameVersions().isEmpty()) l.add(line("Minecraft: " + String.join(", ", s.release().gameVersions())));
        }
        MatchService ms = gui.plugin().matches();
        if (s.kind() == UpdateService.Kind.PENDING_MATCH && ms != null) {
            Optional<MatchService.PendingChoice> pc = ms.pendingFor(s.plugin().name());
            pc.ifPresent(c -> c.candidates().forEach(r -> l.add(line("- " + r.name() + " (" + r.sourceId() + ")"))));
        }
        if (s.error() != null) l.add(Component.text("Ursache: " + s.error(), NamedTextColor.RED));
        return l;
    }

    private static Component line(String text) { return Component.text(text, NamedTextColor.GRAY); }

    @Override
    protected Button emptyButton() { return Button.display(gui.item("results-empty", Material.STRUCTURE_VOID).build()); }

    @Override
    protected void extras() {
        set(Slots.EXTRA_LEFT, Button.of(gui.item("results-filter", Material.HOPPER,
                ph("filter", gui.config().label("filter-" + filter.name().toLowerCase(Locale.ROOT)))).build(), Perm.CHECK,
                c -> new ResultsMenu(gui, viewer, filter.next()).open()));
        set(Slots.EXTRA_RIGHT, Button.of(gui.item("results-detail", Material.LANTERN,
                ph("mode", gui.config().label(gui.detailed(viewer) ? "mode-detailed" : "mode-quick"))).build(), Perm.CHECK, c -> {
            gui.setDetailed(viewer, !gui.detailed(viewer));
            render();
        }));
    }

    @Override protected void back() { new CheckMenu(gui, viewer).open(); }
}
