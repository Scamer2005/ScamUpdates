package de.scamupdates.gui;

import de.scamupdates.compat.CompatInfo;
import de.scamupdates.crafty.ControlStatus;
import de.scamupdates.net.PaperDownloads;
import de.scamupdates.security.Perm;
import de.scamupdates.update.UpdateService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

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
        set(29, Button.of(gui.item("menu-check", Material.SPYGLASS).build(), Perm.CHECK, c -> new CheckMenu(gui, viewer).open()));
        set(33, Button.of(gui.item("menu-history", Material.WRITTEN_BOOK).build(), Perm.HISTORY, c -> gui.openHistory(viewer)));
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
    }

    private String fingerprint() {
        UpdateService us = gui.plugin().updates();
        ControlStatus cs = gui.plugin().controlStatus();
        PaperDownloads.PaperStatus ps = us == null ? null : us.paperStatus();
        return gui.plugin().state().state() + "|" + Bukkit.getPluginManager().getPlugins().length + "|"
                + (us == null ? -1 : us.snapshot().time()) + "|" + cs.ready() + cs.time() + "|"
                + (ps == null ? "" : ps.latestBuild() + ps.error()) + "|"
                + (gui.plugin().matches() == null ? 0 : gui.plugin().matches().pendingChoices().size());
    }

    @Override protected boolean needsRender() { return !fingerprint().equals(lastFingerprint); }
}
