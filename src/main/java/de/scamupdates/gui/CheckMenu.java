package de.scamupdates.gui;

import de.scamupdates.messaging.Messages;
import de.scamupdates.net.PaperDownloads;
import de.scamupdates.security.Perm;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.UpdateService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static de.scamupdates.messaging.Messages.ph;

/**
 * Update-Check: Startauswahl (Schnell/Detailliert), Fortschrittsanzeige mit Animation und Abbrechen-Barrier
 * sowie Zusammenfassung. Die Pruefung selbst laeuft im UpdateService weiter, wenn das Menue geschlossen wird;
 * beim erneuten Oeffnen wird der aktuelle Stand gezeigt.
 */
final class CheckMenu extends Menu {
    private String lastView = "";

    CheckMenu(GuiService gui, Player viewer) { super(gui, viewer); }

    @Override protected int rows() { return 6; }

    @Override protected Component title() { return gui.config().title("check"); }

    @Override
    protected void build() {
        frame();
        UpdateService us = gui.plugin().updates();
        backButton(() -> new MainMenu(gui, viewer).open());
        closeButton();
        if (us == null) return;
        if (us.checkRunning()) buildRunning(us);
        else if (us.snapshot().time() > 0) buildSummary(us);
        else buildStart();
        lastView = view(us);
    }

    private String view(UpdateService us) {
        if (us.checkRunning()) {
            UpdateService.CheckProgress p = us.checkProgress();
            return "R|" + (p == null ? "" : p.done() + p.current()) + "|" + (gui.config().animations() ? ticks : 0);
        }
        return "S|" + us.snapshot().time() + "|" + gui.detailed(viewer);
    }

    @Override
    protected boolean needsRender() {
        UpdateService us = gui.plugin().updates();
        return us != null && !view(us).equals(lastView);
    }

    private void buildStart() {
        set(30, Button.of(gui.item("check-quick", Material.CLOCK).build(), Perm.CHECK, c -> start(false)));
        set(32, Button.of(gui.item("check-detailed", Material.COMPASS).build(), Perm.CHECK, c -> start(true)));
    }

    private void buildRunning(UpdateService us) {
        UpdateService.CheckProgress pr = us.checkProgress();
        int total = pr == null ? 0 : pr.total();
        int done = pr == null ? 0 : pr.done();
        int pct = total == 0 ? 0 : Math.min(100, done * 100 / total);
        int filled = pct * 7 / 100;
        Material doneM = gui.config().design("progress-done", Material.LIME_STAINED_GLASS_PANE);
        Material todoM = gui.config().design("progress-todo", Material.GRAY_STAINED_GLASS_PANE);
        Material accent = gui.config().design("progress-accent", Material.YELLOW_STAINED_GLASS_PANE);
        int remaining = 7 - filled;
        int accentAt = gui.config().animations() && remaining > 0 ? filled + (int) (ticks % remaining) : -1;
        for (int i = 0; i < 7; i++) {
            Material m = i < filled ? doneM : (i == accentAt ? accent : todoM);
            set(28 + i, ItemBuilder.of(m).name(Component.text(pct + "%")).build());
        }
        String current = pr == null || pr.current().isEmpty() ? "-" : pr.current();
        set(22, gui.item("check-progress", Material.CLOCK, ph("percent", pct), ph("done", done), ph("total", total),
                ph("current", current), ph("elapsed", GuiService.duration(System.currentTimeMillis() - (pr == null ? System.currentTimeMillis() : pr.startedAt()))),
                ph("id", pr == null ? "-" : pr.operationId())).build());
        if (pr != null && "Paper".equals(pr.current())) set(20, gui.item("check-progress-paper", Material.PAPER).build());
        set(Slots.CONFIRM, Button.of(gui.item("nav-cancel", Material.BARRIER).build(), Perm.CHECK, c -> askCancel(us)));
    }

    private void askCancel(UpdateService us) {
        new YesNoMenu(gui, viewer, gui.config().text("text.check.cancel-question"),
                () -> {
                    if (us.cancelCheck()) viewer.sendMessage(gui.config().text("text.check.cancel-requested"));
                    new CheckMenu(gui, viewer).open();
                },
                () -> new CheckMenu(gui, viewer).open()).open();
    }

    private void buildSummary(UpdateService us) {
        UpdateService.Snapshot s = us.snapshot();
        long updates = s.count(UpdateService.Kind.UPDATE_AVAILABLE);
        long current = s.count(UpdateService.Kind.UP_TO_DATE) + s.count(UpdateService.Kind.NO_RELEASE);
        long open = s.count(UpdateService.Kind.PENDING_MATCH);
        long problems = s.count(UpdateService.Kind.ERROR) + s.count(UpdateService.Kind.UNMATCHED) + s.count(UpdateService.Kind.INCOMPATIBLE);
        long excluded = s.count(UpdateService.Kind.EXCLUDED) + s.count(UpdateService.Kind.PENDING_RESTART);

        set(20, Button.of(gui.item("sum-updates", Material.LIME_DYE, ph("count", updates)).build(), Perm.CHECK,
                c -> new ResultsMenu(gui, viewer, ResultsMenu.Filter.UPDATES).open()));
        set(21, Button.display(gui.item("sum-current", Material.LIGHT_BLUE_DYE, ph("count", current)).build()));
        set(22, Button.of(gui.item("sum-open", Material.YELLOW_DYE, ph("count", open)).build(), Perm.CHECK,
                c -> new ResultsMenu(gui, viewer, ResultsMenu.Filter.PROBLEMS).open()));
        set(23, Button.of(gui.item("sum-problems", Material.RED_DYE, ph("count", problems)).build(), Perm.CHECK,
                c -> new ResultsMenu(gui, viewer, ResultsMenu.Filter.PROBLEMS).open()));
        set(24, Button.display(gui.item("sum-excluded", Material.GRAY_DYE, ph("count", excluded)).build()));

        PaperDownloads.PaperStatus ps = us.paperStatus();
        set(30, Button.display(gui.item("sum-paper", Material.PAPER, ph("mc", gui.plugin().compat().minecraft()),
                ph("build", gui.plugin().compat().paperBuild() > 0 ? "#" + gui.plugin().compat().paperBuild() : "?"),
                ph("latest", ps == null || ps.failed() || ps.latestBuild() <= 0 ? "?" : "#" + ps.latestBuild()),
                ph("hint", gui.paperHint(ps))).build()));
        set(32, Button.of(gui.item("sum-show-all", Material.BOOK).build(), Perm.CHECK,
                c -> new ResultsMenu(gui, viewer, ResultsMenu.Filter.ALL).open()));
        set(40, Button.of(gui.item("sum-rerun", Material.REPEATER).build(), Perm.CHECK, c -> start(c.isRight())));
    }

    private void start(boolean detailed) {
        UpdateService us = gui.plugin().updates();
        if (us == null) return;
        long wait = us.secondsUntilNextManualCheck();
        if (wait > 0) {
            viewer.sendMessage(gui.config().text("text.check.cooldown", ph("seconds", wait)));
            return;
        }
        gui.setDetailed(viewer, detailed);
        us.check(viewer.getName()).whenComplete((snap, err) -> {
            if (err == null) return;
            Throwable t = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
            if (t instanceof CancellationException) return;
            gui.plugin().runMain(() -> {
                if (t instanceof UpdateService.BusyException b) {
                    viewer.sendMessage(gui.plugin().messages().render("busy", Messages.ph("operation", String.valueOf(b.getMessage()))));
                } else if (t instanceof StateManager.OfflineException oe) {
                    viewer.sendMessage(gui.plugin().messages().render("offline.action-blocked",
                            Messages.ph("reason", gui.plugin().log().redact(String.valueOf(oe.getMessage())))));
                } else {
                    gui.plugin().log().error("Update-Check aus dem Menue fehlgeschlagen", t);
                    viewer.sendMessage(gui.plugin().messages().render("check.failed", Messages.ph("error",
                            gui.plugin().log().redact(String.valueOf(t.getMessage())))));
                }
            });
        });
        viewer.sendMessage(gui.config().text("text.check.started"));
        render();
    }
}
