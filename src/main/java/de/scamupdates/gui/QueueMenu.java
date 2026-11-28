package de.scamupdates.gui;

import de.scamupdates.queue.QueueService;
import de.scamupdates.security.Perm;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.UpdateService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static de.scamupdates.messaging.Messages.ph;

/**
 * Uebersicht der dauerhaften Update-Warteschlange mit Live-Auswertung,
 * Pruefen, Stapel-Download bereiter Eintraege und Einzelaktionen pro Eintrag.
 */
final class QueueMenu extends PagedMenu<QueueService.EvaluatedEntry> {

    private long lastSnapshotTime = -1L;
    private int lastQueueSize = -1;

    QueueMenu(GuiService gui, Player viewer) { super(gui, viewer); }

    @Override protected int rows() { return 6; }

    @Override protected Component title() { return gui.config().title("queue", pageResolvers()); }

    @Override
    protected List<QueueService.EvaluatedEntry> entries() {
        UpdateService us = gui.plugin().updates();
        UpdateService.Snapshot snap = us == null ? new UpdateService.Snapshot(0, List.of()) : us.snapshot();
        lastSnapshotTime = snap.time();
        Set<String> installed = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(Plugin::getName).collect(Collectors.toSet());
        List<QueueService.EvaluatedEntry> evals = gui.plugin().queueService() == null
                ? List.of()
                : gui.plugin().queueService().evaluate(snap, gui.plugin().matches(), installed, System.currentTimeMillis());
        lastQueueSize = evals.size();
        return evals;
    }

    @Override
    protected Button button(QueueService.EvaluatedEntry ev) {
        String id;
        Material fallback;
        switch (ev.state()) {
            case READY -> { id = "queue-entry-ready"; fallback = Material.LIME_DYE; }
            case AMBIGUOUS -> { id = "queue-entry-ambiguous"; fallback = Material.YELLOW_DYE; }
            case SNOOZED -> { id = "queue-entry-snoozed"; fallback = Material.LIGHT_BLUE_DYE; }
            case WAITING, PENDING_RESTART -> { id = "queue-entry-waiting"; fallback = Material.CLOCK; }
            default -> { id = "queue-entry-problem"; fallback = Material.RED_DYE; }
        }
        String installed = ev.entry().installedVersion().isBlank() ? "?" : ev.entry().installedVersion();
        String target = ev.candidate() != null ? ev.candidate().toVersion()
                : (ev.entry().targetVersion().isBlank() ? "-" : ev.entry().targetVersion());
        String source = ev.entry().sourceId().isBlank() ? "-" : ev.entry().sourceId();
        ItemBuilder b = gui.item(id, fallback,
                ph("plugin", ev.entry().plugin()),
                ph("installed", installed),
                ph("target", target),
                ph("source", source),
                ph("candidates", ev.candidates().size()),
                ph("status", stateLabel(ev.state())),
                ph("detail", ev.detail()));
        return Button.of(b.build(), Perm.QUEUE, c -> gui.openQueueEntry(viewer, ev.entry().plugin()));
    }

    static String stateLabel(QueueService.EntryState s) {
        return switch (s) {
            case READY -> "Bereit";
            case AMBIGUOUS -> "Kandidaten waehlen";
            case WAITING -> "Wartend";
            case SNOOZED -> "Spaeter";
            case EXCLUDED -> "Ausgeschlossen";
            case PENDING_RESTART -> "Neustart noetig";
            case INCOMPATIBLE -> "Inkompatibel";
            case UNMATCHED -> "Keine Quelle";
            case ERROR -> "Fehler";
            case MISSING_PLUGIN -> "Nicht installiert";
        };
    }

    @Override
    protected Button emptyButton() {
        return Button.display(gui.item("queue-empty", Material.STRUCTURE_VOID).build());
    }

    @Override
    protected void extras(List<QueueService.EvaluatedEntry> all) {
        set(Slots.EXTRA_LEFT, Button.of(gui.item("queue-check-all", Material.SPYGLASS).build(), Perm.QUEUE, c -> recheckAll()));

        long readyCount = all.stream()
                .filter(e -> e.state() == QueueService.EntryState.READY && e.candidate() != null
                        && e.candidate().release().downloadUrl() != null)
                .count();
        if (readyCount > 0) {
            set(Slots.EXTRA_RIGHT, Button.of(gui.item("queue-download-all", Material.EMERALD, ph("count", readyCount)).build(),
                    Perm.QUEUE, c -> {
                        viewer.closeInventory();
                        gui.plugin().command().requestQueueDownloadAll(viewer);
                    }));
        }
    }

    private void recheckAll() {
        UpdateService us = gui.plugin().updates();
        if (us == null) return;
        viewer.sendMessage(gui.plugin().messages().render("check.started"));
        us.check(viewer.getName()).whenComplete((snap, err) -> gui.plugin().runMain(() -> {
            if (err != null) {
                Throwable t = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                if (t instanceof UpdateService.BusyException b) {
                    viewer.sendMessage(gui.plugin().messages().render("busy", ph("operation", String.valueOf(b.getMessage()))));
                } else if (t instanceof StateManager.OfflineException oe) {
                    viewer.sendMessage(gui.plugin().messages().render("offline.action-blocked",
                            ph("reason", gui.plugin().log().redact(String.valueOf(oe.getMessage())))));
                } else {
                    viewer.sendMessage(gui.plugin().messages().render("check.failed",
                            ph("error", gui.plugin().log().redact(String.valueOf(t.getMessage())))));
                }
                return;
            }
            gui.plugin().notifier().broadcastQueueNotifications();
            if (isViewing()) {
                render();
            }
        }));
    }

    @Override
    protected boolean needsRender() {
        UpdateService us = gui.plugin().updates();
        int curSize = gui.plugin().queue() == null ? 0 : gui.plugin().queue().all().size();
        return (us != null && us.snapshot().time() != lastSnapshotTime) || curSize != lastQueueSize;
    }

    @Override protected void back() { gui.openMain(viewer); }
}
