package de.scamupdates.gui;

import de.scamupdates.compat.CompatInfo;
import de.scamupdates.crafty.ControlStatus;
import de.scamupdates.net.PaperDownloads;
import de.scamupdates.restart.RestartCoordinator;
import de.scamupdates.security.Perm;
import de.scamupdates.update.UpdateService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static de.scamupdates.messaging.Messages.ph;

/** Hauptmenue mit Statusanzeige (aktualisiert sich selbststaendig) und den Funktionsbereichen. */
final class MainMenu extends Menu {
    private String lastFingerprint = "";

    MainMenu(GuiService gui, Player viewer) { super(gui, viewer); }

    @Override protected int rows() { return 6; }

    @Override protected Component title() { return gui.config().title("main"); }

    @Override
    protected void build() {
        frame();
        status();
        int queueCount = gui.plugin().queue() == null ? 0 : gui.plugin().queue().all().size();
        ZoneId zone = gui.plugin().config().restartTimezone();
        long now = System.currentTimeMillis();
        Optional<RestartCoordinator.ActiveCountdown> cd = gui.plugin().restartCoordinator() == null
                ? Optional.empty() : gui.plugin().restartCoordinator().activeCountdown();
        String cdText = cd.map(c -> "in " + RestartCoordinator.formatDuration(c.remainingSeconds(now)))
                .orElse("kein Countdown");

        set(29, Button.of(gui.item("menu-check", Material.SPYGLASS).build(), Perm.CHECK,
                c -> new CheckMenu(gui, viewer).open()));
        set(30, Button.of(gui.item("menu-single", Material.ENCHANTED_BOOK).build(), Perm.UPDATE_SINGLE,
                c -> gui.openLibrary(viewer)));
        set(31, Button.of(gui.item("menu-queue", Material.HOPPER, ph("count", queueCount)).build(), Perm.QUEUE,
                c -> gui.openQueue(viewer)));
        set(32, Button.of(gui.item("menu-schedule", Material.REPEATER, ph("zone", zone.getId())).build(), Perm.SCHEDULE,
                c -> gui.openSchedules(viewer)));
        set(33, Button.of(gui.item("menu-onetime", Material.REDSTONE_TORCH, ph("countdown", cdText)).build(), Perm.SCHEDULE,
                c -> gui.openOneTime(viewer)));
        set(40, Button.of(gui.item("menu-history", Material.WRITTEN_BOOK).build(), Perm.HISTORY,
                c -> gui.openHistory(viewer)));
        closeButton();
        lastFingerprint = fingerprint();
    }

    private void status() {
        CompatInfo ci = gui.plugin().compat();
        UpdateService us = gui.plugin().updates();
        PaperDownloads.PaperStatus ps = us == null ? null : us.paperStatus();
        int updates = us == null ? 0 : us.snapshot().updates().size();
        int pending = gui.plugin().matches() == null ? 0 : gui.plugin().matches().pendingChoices().size();
        ControlStatus cs = gui.plugin().controlStatus();
        long now = System.currentTimeMillis();
        ZoneId zone = gui.plugin().config().restartTimezone();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(zone);
        Optional<RestartCoordinator.ActiveCountdown> cd = gui.plugin().restartCoordinator() == null
                ? Optional.empty() : gui.plugin().restartCoordinator().activeCountdown();
        String cdText = cd.map(c -> RestartCoordinator.formatDuration(c.remainingSeconds(now))).orElse("inaktiv");
        String nextPlan = gui.plugin().restartCoordinator() == null
                ? (gui.plugin().restartSchedule() == null ? "-"
                : gui.plugin().restartSchedule().nextTriggerMillis(Instant.ofEpochMilli(now), zone)
                        .map(t -> fmt.format(Instant.ofEpochMilli(t))).orElse("keiner"))
                : gui.plugin().restartCoordinator().nextEffectiveTriggerMillis(now)
                        .map(t -> fmt.format(Instant.ofEpochMilli(t))).orElse("keiner");
        int queueCount = gui.plugin().queue() == null ? 0 : gui.plugin().queue().all().size();

        set(10, gui.item("status-minecraft", Material.GRASS_BLOCK, ph("version", ci.minecraft()),
                ph("status", gui.compatLabel()), ph("detail", ci.detail())).build());
        set(11, gui.item("status-paper", Material.PAPER,
                ph("build", ci.paperBuild() > 0 ? "#" + ci.paperBuild() : "unbekannt"),
                ph("latest", ps == null || ps.failed() || ps.latestBuild() <= 0 ? "unbekannt" : "#" + ps.latestBuild()),
                ph("hint", gui.paperHint(ps))).build());
        set(12, gui.item("status-plugins", Material.BOOKSHELF, ph("count", Bukkit.getPluginManager().getPlugins().length)).build());
        set(13, gui.item("status-updates", Material.EXPERIENCE_BOTTLE, ph("count", updates), ph("pending", pending)).build());
        set(14, Button.of(gui.item("status-crafty", Material.COMPARATOR,
                ph("mode", cs.craftyEnabled() ? gui.config().label("crafty-mode-crafty") : gui.config().label("crafty-mode-local")),
                ph("state", cs.ready() ? gui.config().label("control-ready") : gui.config().label("control-not-ready")),
                ph("detail", cs.detail())).build(), Perm.CHECK, c -> gui.plugin().refreshControlStatus(viewer)));
        set(15, gui.item("status-state", Material.BEACON, ph("state", gui.plugin().state().state()),
                ph("detail", gui.plugin().state().details().isEmpty() ? "-" : String.join("; ", gui.plugin().state().details()))).build());
        set(16, gui.item("status-restart", Material.CLOCK, ph("countdown", cdText), ph("next", nextPlan),
                ph("queued", queueCount), ph("zone", zone.getId())).build());
    }

    private String fingerprint() {
        UpdateService us = gui.plugin().updates();
        ControlStatus cs = gui.plugin().controlStatus();
        PaperDownloads.PaperStatus ps = us == null ? null : us.paperStatus();
        long now = System.currentTimeMillis();
        String cdFp = gui.plugin().restartCoordinator() == null ? ""
                : gui.plugin().restartCoordinator().activeCountdown()
                        .map(c -> c.id() + ":" + c.remainingSeconds(now)).orElse("-");
        int queueSize = gui.plugin().queue() == null ? 0 : gui.plugin().queue().all().size();
        int ruleSize = gui.plugin().restartSchedule() == null ? 0 : gui.plugin().restartSchedule().allRules().size();
        long nextTrig = gui.plugin().restartCoordinator() == null ? -1L
                : gui.plugin().restartCoordinator().nextEffectiveTriggerMillis(now).orElse(-1L);
        return gui.plugin().state().state() + "|" + Bukkit.getPluginManager().getPlugins().length + "|"
                + (us == null ? -1 : us.snapshot().time()) + "|" + cs.ready() + cs.time() + "|"
                + (ps == null ? "" : ps.latestBuild() + ps.error()) + "|"
                + (gui.plugin().matches() == null ? 0 : gui.plugin().matches().pendingChoices().size()) + "|"
                + cdFp + "|" + queueSize + "|" + ruleSize + "|" + nextTrig;
    }

    @Override protected boolean needsRender() { return !fingerprint().equals(lastFingerprint); }
}
