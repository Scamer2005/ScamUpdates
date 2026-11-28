package de.scamupdates.gui;

import de.scamupdates.restart.RestartCoordinator;
import de.scamupdates.restart.RestartScheduleStore;
import de.scamupdates.restart.RestartService;
import de.scamupdates.security.Perm;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import static de.scamupdates.messaging.Messages.ph;

/**
 * Uebersicht aller wiederkehrenden Neustart-Zeitplaene (stuendlich, taeglich, woechentlich, mehrere Wochentage)
 * mit naechstem Ausloesezeitpunkt in Europe/Berlin und Neustart-Bereitschaft.
 */
final class ScheduledRestartsMenu extends PagedMenu<RestartScheduleStore.ScheduleRule> {

    private String lastCountdown = "";

    ScheduledRestartsMenu(GuiService gui, Player viewer) { super(gui, viewer); }

    @Override protected int rows() { return 6; }

    @Override protected Component title() { return gui.config().title("schedules", pageResolvers()); }

    @Override
    protected List<RestartScheduleStore.ScheduleRule> entries() {
        return gui.plugin().restartSchedule() == null ? List.of() : gui.plugin().restartSchedule().allRules();
    }

    @Override
    protected Button button(RestartScheduleStore.ScheduleRule r) {
        ZoneId zone = gui.plugin().config().restartTimezone();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone);
        String freqLabel = gui.config().label("freq-" + r.frequency().name().toLowerCase(Locale.ROOT));
        String time = r.frequency() == RestartScheduleStore.Frequency.HOURLY
                ? String.format(":%02d", r.minute())
                : String.format("%02d:%02d", r.hour(), r.minute());
        String days = formatDays(r);
        String next = r.nextTriggerMillis(Instant.now(), zone).map(t -> fmt.format(Instant.ofEpochMilli(t))).orElse("-");
        String pendingLabel = gui.config().label(r.requirePending() ? "yes-label" : "no-label");
        String label = r.label().isBlank() ? "Neustart-Plan (" + r.id() + ")" : r.label();
        String itemId = r.enabled() ? "sched-rule-on" : "sched-rule-off";
        Material fb = r.enabled() ? Material.LIME_DYE : Material.GRAY_DYE;

        return Button.of(gui.item(itemId, fb,
                ph("label", label),
                ph("freq", freqLabel),
                ph("time", time),
                ph("zone", zone.getId()),
                ph("days", days),
                ph("next", next),
                ph("pending", pendingLabel)).build(), Perm.SCHEDULE, c -> gui.openScheduleEdit(viewer, r));
    }

    static String formatDays(RestartScheduleStore.ScheduleRule r) {
        return switch (r.frequency()) {
            case HOURLY -> "jede Stunde";
            case DAILY -> "taeglich";
            case WEEKLY, MULTI_DAY -> r.daysOfWeek().stream()
                    .sorted()
                    .map(d -> d.getDisplayName(TextStyle.SHORT, Locale.GERMAN))
                    .collect(Collectors.joining(", "));
        };
    }

    @Override
    protected Button emptyButton() {
        return Button.display(gui.item("sched-empty", Material.STRUCTURE_VOID).build());
    }

    @Override
    protected void extras() {
        set(Slots.EXTRA_LEFT, Button.of(gui.item("sched-add", Material.NETHER_STAR).build(), Perm.SCHEDULE, c -> {
            if (!gui.plugin().config().restartsEnabled()) {
                viewer.sendMessage(gui.plugin().messages().render("schedule.disabled"));
                return;
            }
            gui.openScheduleEdit(viewer, null);
        }));

        RestartService rs = gui.plugin().restart();
        RestartService.Readiness rd = rs == null
                ? new RestartService.Readiness(false, RestartService.RestartMode.UNAVAILABLE, "Service inaktiv")
                : rs.readiness();
        set(Slots.EXTRA_RIGHT, gui.item("sched-readiness", Material.COMPARATOR,
                ph("mode", rd.mode().name()),
                ph("ready", gui.config().label(rd.ready() ? "yes-label" : "no-label")),
                ph("detail", rd.detail())).build());

        long now = System.currentTimeMillis();
        Optional<RestartCoordinator.ActiveCountdown> active = gui.plugin().restartCoordinator() == null
                ? Optional.empty() : gui.plugin().restartCoordinator().activeCountdown();
        if (active.isPresent()) {
            RestartCoordinator.ActiveCountdown cd = active.get();
            String rem = RestartCoordinator.formatDuration(cd.remainingSeconds(now));
            lastCountdown = cd.id() + ":" + rem;
            set(Slots.CONFIRM, Button.of(gui.item("sched-active", Material.CLOCK,
                    ph("remaining", rem),
                    ph("reason", cd.reason().isBlank() ? "-" : cd.reason())).build(),
                    Perm.SCHEDULE, c -> gui.openOneTime(viewer)));
        } else {
            lastCountdown = "-";
            set(Slots.CONFIRM, Button.of(gui.item("menu-onetime", Material.REDSTONE_TORCH,
                    ph("countdown", "kein Countdown")).build(), Perm.SCHEDULE, c -> gui.openOneTime(viewer)));
        }
    }

    @Override
    protected boolean needsRender() {
        if (gui.plugin().restartCoordinator() == null) return false;
        long now = System.currentTimeMillis();
        String cur = gui.plugin().restartCoordinator().activeCountdown()
                .map(c -> c.id() + ":" + RestartCoordinator.formatDuration(c.remainingSeconds(now))).orElse("-");
        return !cur.equals(lastCountdown);
    }

    @Override protected void back() { gui.openMain(viewer); }
}
