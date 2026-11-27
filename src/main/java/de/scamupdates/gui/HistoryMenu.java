package de.scamupdates.gui;

import de.scamupdates.history.HistoryEntry;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.security.Perm;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static de.scamupdates.messaging.Messages.ph;

/** Update-Protokoll, sortierbar (neueste, aelteste, Probleme zuerst). Das Lesen der Datei erfolgt beim Oeffnen/Sortieren. */
final class HistoryMenu extends PagedMenu<HistoryEntry> {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final HistoryStore.Sort sort;
    private final List<HistoryEntry> data;

    HistoryMenu(GuiService gui, Player viewer, HistoryStore.Sort sort) {
        super(gui, viewer);
        this.sort = sort;
        this.data = gui.plugin().history().list(sort);
    }

    @Override protected int rows() { return 6; }

    @Override protected Component title() { return gui.config().title("history", pageResolvers()); }

    @Override protected List<HistoryEntry> entries() { return data; }

    @Override
    protected Button button(HistoryEntry e) {
        String id;
        Material fallback;
        switch (e.result() == null ? "" : e.result()) {
            case "OK" -> { id = "history-ok"; fallback = Material.LIME_DYE; }
            case "WARN" -> { id = "history-warn"; fallback = Material.YELLOW_DYE; }
            case "FAILED" -> { id = "history-failed"; fallback = Material.RED_DYE; }
            default -> { id = "history-info"; fallback = Material.LIGHT_BLUE_DYE; }
        }
        int n = e.details().size();
        ItemBuilder b = gui.item(id, fallback, ph("action", e.action()), ph("time", FMT.format(Instant.ofEpochMilli(e.time()))),
                ph("actor", e.actor()), ph("message", e.message().isBlank() ? "-" : e.message()),
                ph("mc", e.mcTarget().isBlank() ? "-" : e.mcTarget()), ph("details", n == 0 ? "" : n + " Plugin(s):"));
        int shown = 0;
        for (HistoryEntry.Detail d : e.details()) {
            if (shown++ >= 6) {
                b.addLore(Component.text("... +" + (n - 6) + " weitere", NamedTextColor.DARK_GRAY));
                break;
            }
            b.addLore(Component.text(d.plugin() + " " + d.from() + " -> " + d.to() + " [" + d.status() + "]", NamedTextColor.GRAY));
        }
        return Button.display(b.build());
    }

    @Override protected Button emptyButton() { return Button.display(gui.item("history-empty", Material.STRUCTURE_VOID).build()); }

    @Override
    protected void extras() {
        HistoryStore.Sort next = HistoryStore.Sort.values()[(sort.ordinal() + 1) % HistoryStore.Sort.values().length];
        String label = gui.config().label(switch (sort) {
            case NEWEST -> "sort-newest";
            case OLDEST -> "sort-oldest";
            case PROBLEMS_FIRST -> "sort-problems";
        });
        set(Slots.EXTRA_LEFT, Button.of(gui.item("history-sort", Material.COMPARATOR, ph("sort", label)).build(), Perm.HISTORY,
                c -> new HistoryMenu(gui, viewer, next).open()));
    }

    @Override protected void back() { new MainMenu(gui, viewer).open(); }

    @SuppressWarnings("unused")
    private static String key(Enum<?> e) { return e.name().toLowerCase(Locale.ROOT); }
}
