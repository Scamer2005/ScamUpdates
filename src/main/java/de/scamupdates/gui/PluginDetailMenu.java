package de.scamupdates.gui;

import de.scamupdates.backup.BackupManager;
import de.scamupdates.history.HistoryEntry;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.match.MatchService;
import de.scamupdates.match.MatchStore;
import de.scamupdates.messaging.Messages;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.scan.PluginScanner;
import de.scamupdates.security.Perm;
import de.scamupdates.sources.McVersionMatcher;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.TransactionStore;
import de.scamupdates.update.UpdateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.text.DateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import static de.scamupdates.messaging.Messages.ph;

/**
 * Detailansicht fuer ein einzelnes Plugin mit Versionsstand, Quelle, Kompatibilitaet,
 * pluginbezogenem Verlauf, Backup-Informationen sowie Aktionen (Pruefen, Download, Quelle waehlen,
 * Warteschlange, Ausschliessen/Einbeziehen, Backup anzeigen & wiederherstellen).
 */
final class PluginDetailMenu extends Menu {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());

    private final String pluginName;
    private String lastFingerprint = "";

    PluginDetailMenu(GuiService gui, Player viewer, String pluginName) {
        super(gui, viewer);
        this.pluginName = pluginName;
    }

    @Override protected int rows() { return 6; }

    @Override protected Component title() { return gui.config().title("detail", ph("plugin", pluginName)); }

    @Override
    protected void build() {
        frame();
        backButton(() -> gui.openLibrary(viewer));
        closeButton();

        Optional<PluginInfo> infoOpt = PluginScanner.collectMetadata().stream()
                .filter(p -> p.name().equalsIgnoreCase(pluginName))
                .findFirst();
        if (infoOpt.isEmpty()) {
            set(22, ItemBuilder.of(Material.BARRIER).name(Component.text(pluginName + " nicht installiert", NamedTextColor.RED)).build());
            lastFingerprint = "missing";
            return;
        }
        PluginInfo info = infoOpt.get();
        UpdateService us = gui.plugin().updates();
        UpdateService.PluginStatus st = us == null ? null : us.statusFor(info.name()).orElse(null);
        Optional<TransactionStore.Pending> pendingTx = gui.plugin().transactions().get(info.name());
        Optional<BackupManager.Entry> backup = gui.plugin().backups().get(info.name());
        Optional<MatchService.PendingChoice> pendingMatch = gui.plugin().matches().pendingFor(info.name());
        Optional<MatchStore.Exclusion> exclusion = gui.plugin().matches().store().exclusion(info.name());
        Optional<ProjectRef> assigned = gui.plugin().matches().store().assignment(info.name());
        boolean queued = gui.plugin().queue() != null && gui.plugin().queue().contains(info.name());

        String statusText = pendingTx.isPresent() ? "Vorgemerkt (Neustart noetig)"
                : (pendingMatch.isPresent() ? "Mehrere Kandidaten (Auswahl noetig)"
                : (exclusion.isPresent() ? "Ausgeschlossen" : (st == null ? "noch nicht geprueft" : st.kind().name())));
        String latest = st != null && st.release() != null ? st.release().version() : "-";
        String channel = st != null && st.release() != null ? st.release().channel().name().toLowerCase(Locale.ROOT) : "-";
        String detail = st != null && st.error() != null ? st.error() : (pendingTx.map(t -> "Ziel: " + t.toVersion()).orElse("-"));

        ProjectRef project = st != null && st.project() != null ? st.project() : assigned.orElse(null);
        String source = project == null ? "-" : project.sourceId();
        String projectId = project == null ? "-" : project.projectId();
        String url = project == null || project.url().isBlank() ? "-" : project.url();

        String mc = Bukkit.getMinecraftVersion();
        String pluginMc = st != null && st.release() != null && !st.release().gameVersions().isEmpty()
                ? String.join(", ", st.release().gameVersions())
                : "keine Angabe";
        if (st != null && st.kind() == UpdateService.Kind.INCOMPATIBLE) {
            pluginMc = pluginMc + " (nicht kompatibel!)";
        } else if (st != null && st.release() != null && !st.release().gameVersions().isEmpty()
                && McVersionMatcher.anyMatches(st.release().gameVersions(), mc)) {
            pluginMc = pluginMc + " (kompatibel)";
        }

        // Info-Reihe (Reihe 1)
        set(10, gui.item("detail-plugin", Material.BOOK, ph("plugin", info.name()), ph("installed", info.version()),
                ph("status", statusText), ph("queued", queued ? "ja" : "nein")).build());
        set(11, gui.item("detail-update", Material.EXPERIENCE_BOTTLE, ph("latest", latest),
                ph("channel", channel), ph("detail", detail)).build());
        set(12, gui.item("detail-source", Material.COMPASS, ph("source", source),
                ph("project", projectId), ph("url", url)).build());
        set(13, gui.item("detail-compat", Material.GRASS_BLOCK, ph("mc", mc),
                ph("server-compat", gui.compatLabel()), ph("plugin-mc", pluginMc)).build());

        if (backup.isPresent()) {
            BackupManager.Entry bk = backup.get();
            String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(bk.createdAt()));
            set(14, Button.of(gui.item("detail-backup", Material.CHEST,
                    ph("status", gui.plugin().backups().verify(bk) ? "intakt" : "beschaedigt"),
                    ph("version", bk.version()), ph("file", bk.fileName()), ph("date", date)).build(),
                    Perm.BACKUP, c -> showBackupInChat(info.name(), bk, date)));
        } else {
            set(14, gui.item("detail-backup", Material.CHEST, ph("status", "kein Backup"),
                    ph("version", "-"), ph("file", "-"), ph("date", "-")).build());
        }

        List<HistoryEntry> pluginHist = historyFor(info.name());
        ItemBuilder hb = gui.item("detail-history", Material.WRITTEN_BOOK, ph("count", pluginHist.size()));
        if (pluginHist.isEmpty()) {
            hb.addLore(Component.text("Keine Eintraege fuer " + info.name(), NamedTextColor.GRAY));
        } else {
            for (int i = 0; i < Math.min(6, pluginHist.size()); i++) {
                HistoryEntry h = pluginHist.get(i);
                hb.addLore(Component.text(FMT.format(Instant.ofEpochMilli(h.time())) + " [" + h.result() + "] " + h.action(), NamedTextColor.GRAY));
            }
        }
        set(15, Button.of(hb.build(), Perm.HISTORY, c -> gui.openHistory(viewer)));

        // Aktions-Reihe (Reihe 3)
        set(28, Button.of(gui.item("detail-check", Material.SPYGLASS).build(), Perm.UPDATE_SINGLE, c -> checkSingle(info.name())));

        boolean canDownload = pendingTx.isEmpty() && pendingMatch.isEmpty()
                && st != null && st.kind() == UpdateService.Kind.UPDATE_AVAILABLE
                && st.release() != null && st.release().downloadUrl() != null;
        if (canDownload) {
            set(29, Button.of(gui.item("detail-download", Material.EMERALD, ph("target", latest)).build(), Perm.UPDATE_SINGLE, c -> {
                viewer.closeInventory();
                gui.plugin().command().requestSingleDownload(viewer, info.name());
            }));
        } else {
            set(29, ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.text("Download nicht moeglich", NamedTextColor.GRAY))
                    .addLore(Component.text("Status: " + statusText, NamedTextColor.DARK_GRAY)).build());
        }

        int candCount = pendingMatch.map(m -> m.candidates().size()).orElse(0);
        if (candCount > 0) {
            set(30, Button.of(gui.item("detail-match", Material.NAME_TAG, ph("count", candCount)).build(), Perm.SOURCES,
                    c -> gui.openMatchChoice(viewer, info.name(), () -> gui.openDetail(viewer, info.name()))));
        } else {
            set(30, gui.item("detail-match", Material.NAME_TAG, ph("count", 0)).build());
        }

        if (queued) {
            set(31, Button.of(gui.item("detail-queue-remove", Material.BARRIER).build(), Perm.QUEUE, c -> {
                gui.plugin().queueService().dismiss(info.name(), viewer.getName());
                viewer.sendMessage(gui.plugin().messages().render("queue.removed", ph("plugin", info.name())));
                render();
            }));
        } else {
            set(31, Button.of(gui.item("detail-queue-add", Material.HOPPER).build(), Perm.QUEUE, c -> {
                gui.plugin().queue().enqueueIfAbsent(info.name(), info.version(), source.equals("-") ? "" : source,
                        st == null ? "QUEUED" : st.kind().name(), viewer.getName());
                viewer.sendMessage(gui.plugin().messages().render("queue.added", ph("plugin", info.name())));
                render();
            }));
        }

        boolean isExcluded = exclusion.isPresent();
        if (isExcluded) {
            set(32, Button.of(gui.item("detail-include", Material.LIME_DYE).build(), Perm.SOURCES, c -> {
                viewer.closeInventory();
                gui.plugin().command().requestExcludeToggle(viewer, info.name(), false);
            }));
        } else {
            set(32, Button.of(gui.item("detail-exclude", Material.GRAY_DYE).build(), Perm.SOURCES, c -> {
                viewer.closeInventory();
                gui.plugin().command().requestExcludeToggle(viewer, info.name(), true);
            }));
        }

        if (backup.isPresent() && pendingTx.isEmpty()) {
            set(33, Button.of(gui.item("detail-restore", Material.ENDER_CHEST, ph("version", backup.get().version())).build(),
                    Perm.RESTORE, c -> {
                        viewer.closeInventory();
                        gui.plugin().command().requestRestore(viewer, info.name());
                    }));
        } else {
            set(33, ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.text(backup.isEmpty() ? "Kein Backup vorhanden" : "Restore blockiert (Neustart noetig)", NamedTextColor.GRAY)).build());
        }

        lastFingerprint = fingerprint();
    }

    private void checkSingle(String name) {
        UpdateService us = gui.plugin().updates();
        if (us == null) return;
        viewer.sendMessage(gui.plugin().messages().render("single.checking", ph("plugin", name)));
        us.checkSingle(name, viewer.getName()).whenComplete((st, err) -> gui.plugin().runMain(() -> {
            if (err != null) {
                Throwable t = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                if (t instanceof UpdateService.BusyException b) {
                    viewer.sendMessage(gui.plugin().messages().render("busy", Messages.ph("operation", String.valueOf(b.getMessage()))));
                } else if (t instanceof StateManager.OfflineException oe) {
                    viewer.sendMessage(gui.plugin().messages().render("offline.action-blocked",
                            Messages.ph("reason", gui.plugin().log().redact(String.valueOf(oe.getMessage())))));
                } else {
                    viewer.sendMessage(gui.plugin().messages().render("check.failed",
                            Messages.ph("error", gui.plugin().log().redact(String.valueOf(t.getMessage())))));
                }
                return;
            }
            gui.plugin().notifier().broadcastQueueNotifications();
            if (isViewing()) {
                render();
            }
        }));
    }

    private void showBackupInChat(String name, BackupManager.Entry bk, String date) {
        viewer.sendMessage(gui.plugin().messages().render("backup.entry",
                ph("plugin", name), ph("version", bk.version()), ph("file", bk.fileName()), ph("date", date)));
    }

    private List<HistoryEntry> historyFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        List<HistoryEntry> out = new ArrayList<>();
        for (HistoryEntry e : gui.plugin().history().list(HistoryStore.Sort.NEWEST)) {
            boolean match = e.message().toLowerCase(Locale.ROOT).contains(lower)
                    || e.details().stream().anyMatch(d -> d.plugin().equalsIgnoreCase(name));
            if (match) out.add(e);
        }
        return out;
    }

    private String fingerprint() {
        UpdateService us = gui.plugin().updates();
        return (us == null ? -1 : us.snapshot().time()) + "|"
                + (us == null ? "" : us.statusFor(pluginName).map(s -> s.kind().name()).orElse("")) + "|"
                + gui.plugin().transactions().get(pluginName).isPresent() + "|"
                + gui.plugin().backups().get(pluginName).isPresent() + "|"
                + gui.plugin().matches().pendingFor(pluginName).isPresent() + "|"
                + gui.plugin().matches().store().exclusion(pluginName).isPresent() + "|"
                + (gui.plugin().queue() != null && gui.plugin().queue().contains(pluginName));
    }

    @Override protected boolean needsRender() { return !fingerprint().equals(lastFingerprint); }
}
