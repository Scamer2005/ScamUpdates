package de.scamupdates.gui;

import de.scamupdates.queue.QueueService;
import de.scamupdates.security.Perm;
import de.scamupdates.update.UpdateService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static de.scamupdates.messaging.Messages.ph;

/**
 * Aktionsmenue fuer einen Eintrag in der Update-Warteschlange:
 * Jetzt aktualisieren (oder Kandidaten waehlen), Spaeter, Nicht mehr erinnern, Plugin-Details.
 */
final class QueueEntryMenu extends Menu {

    private final String pluginName;
    private String lastFingerprint = "";

    QueueEntryMenu(GuiService gui, Player viewer, String pluginName) {
        super(gui, viewer);
        this.pluginName = pluginName;
    }

    @Override protected int rows() { return 5; }

    @Override protected Component title() { return gui.config().title("queue-entry", ph("plugin", pluginName)); }

    @Override
    protected void build() {
        frame();
        set(37, Button.of(gui.config().item("nav-back", Material.ARROW).build(), c -> gui.openQueue(viewer)));
        set(40, Button.of(gui.config().item("nav-close", Material.BARRIER).build(), c -> viewer.closeInventory()));

        UpdateService us = gui.plugin().updates();
        UpdateService.Snapshot snap = us == null ? new UpdateService.Snapshot(0, java.util.List.of()) : us.snapshot();
        Set<String> installed = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(Plugin::getName).collect(Collectors.toSet());
        Optional<QueueService.EvaluatedEntry> opt = gui.plugin().queueService() == null
                ? Optional.empty()
                : gui.plugin().queueService().evaluate(snap, gui.plugin().matches(), installed, System.currentTimeMillis())
                        .stream().filter(e -> e.entry().plugin().equalsIgnoreCase(pluginName)).findFirst();
        if (opt.isEmpty()) {
            set(13, ItemBuilder.of(Material.BARRIER).name(Component.text(pluginName + " nicht in der Warteschlange")).build());
            lastFingerprint = fingerprint();
            return;
        }
        QueueService.EvaluatedEntry ev = opt.get();
        String target = ev.candidate() != null ? ev.candidate().toVersion() : "-";

        // Statuskarte
        set(13, gui.item("detail-plugin", Material.BOOK,
                ph("plugin", ev.entry().plugin()),
                ph("installed", ev.entry().installedVersion().isBlank() ? "?" : ev.entry().installedVersion()),
                ph("status", QueueMenu.stateLabel(ev.state())),
                ph("queued", "ja")).build());

        // Jetzt aktualisieren oder Kandidaten waehlen
        if (ev.state() == QueueService.EntryState.AMBIGUOUS) {
            set(20, Button.of(gui.item("queue-action-choose", Material.YELLOW_CONCRETE,
                    ph("count", ev.candidates().size())).build(), Perm.SOURCES,
                    c -> gui.openMatchChoice(viewer, pluginName, () -> gui.openQueueEntry(viewer, pluginName))));
        } else if ((ev.state() == QueueService.EntryState.READY || ev.state() == QueueService.EntryState.SNOOZED)
                && ev.candidate() != null && ev.candidate().release().downloadUrl() != null) {
            set(20, Button.of(gui.item("queue-action-update", Material.LIME_CONCRETE,
                    ph("target", target)).build(), Perm.QUEUE, c -> {
                viewer.closeInventory();
                gui.plugin().command().requestQueueUpdate(viewer, pluginName);
            }));
        } else {
            set(20, ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.text("Kein Update bereit (" + QueueMenu.stateLabel(ev.state()) + ")")).build());
        }

        // Spaeter
        set(22, Button.of(gui.item("queue-action-later", Material.CLOCK).build(), Perm.QUEUE, c -> {
            gui.plugin().queueService().snooze(pluginName, viewer.getName());
            viewer.sendMessage(gui.plugin().messages().render("queue.snoozed", ph("plugin", pluginName)));
            gui.openQueue(viewer);
        }));

        // Nicht mehr erinnern
        set(24, Button.of(gui.item("queue-action-dismiss", Material.RED_CONCRETE).build(), Perm.QUEUE, c -> {
            gui.plugin().queueService().dismiss(pluginName, viewer.getName());
            viewer.sendMessage(gui.plugin().messages().render("queue.dismissed", ph("plugin", pluginName)));
            gui.openQueue(viewer);
        }));

        // Plugin-Details
        set(31, Button.of(gui.item("queue-action-detail", Material.BOOK).build(), Perm.UPDATE_SINGLE,
                c -> gui.openDetail(viewer, pluginName)));
        lastFingerprint = fingerprint();
    }

    private String fingerprint() {
        UpdateService us = gui.plugin().updates();
        long snapTime = us == null ? -1L : us.snapshot().time();
        String statusKind = us == null ? "" : us.statusFor(pluginName).map(s -> s.kind().name()).orElse("");
        boolean hasPendingMatch = gui.plugin().matches() != null && gui.plugin().matches().pendingFor(pluginName).isPresent();
        String queueMeta = gui.plugin().queue() == null ? ""
                : gui.plugin().queue().get(pluginName)
                        .map(e -> e.lastStatus() + ":" + e.targetVersion() + ":" + e.snoozedUntil())
                        .orElse("missing");
        return snapTime + "|" + statusKind + "|" + hasPendingMatch + "|" + queueMeta;
    }

    @Override
    protected boolean needsRender() {
        return !fingerprint().equals(lastFingerprint);
    }
}
