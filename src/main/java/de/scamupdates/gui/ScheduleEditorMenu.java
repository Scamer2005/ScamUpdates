package de.scamupdates.gui;

import de.scamupdates.restart.RestartScheduleStore;
import de.scamupdates.security.Perm;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static de.scamupdates.messaging.Messages.ph;

/**
 * Editor fuer wiederkehrende Neustart-Plaene (neu oder bestehend).
 * Konflikte und ungueltige Uhrzeiten werden serverseitig geprueft; kein Einmalcode noetig.
 */
final class ScheduleEditorMenu extends Menu {

    private final String ruleId;
    private RestartScheduleStore.Frequency frequency;
    private Set<DayOfWeek> days;
    private int hour;
    private int minute;
    private String label;
    private boolean enabled;
    private boolean requirePending;

    ScheduleEditorMenu(GuiService gui, Player viewer, RestartScheduleStore.ScheduleRule existing) {
        super(gui, viewer);
        if (existing == null) {
            this.ruleId = null;
            this.frequency = RestartScheduleStore.Frequency.DAILY;
            this.days = EnumSet.of(DayOfWeek.MONDAY);
            this.hour = 4;
            this.minute = 0;
            this.label = "Geplanter Neustart";
            this.enabled = true;
            this.requirePending = false;
        } else {
            this.ruleId = existing.id();
            this.frequency = existing.frequency();
            this.days = existing.daysOfWeek().isEmpty() ? EnumSet.of(DayOfWeek.MONDAY) : EnumSet.copyOf(existing.daysOfWeek());
            this.hour = existing.hour();
            this.minute = existing.minute();
            this.label = existing.label();
            this.enabled = existing.enabled();
            this.requirePending = existing.requirePending();
        }
    }

    @Override protected int rows() { return 6; }

    @Override protected Component title() {
        return gui.config().title("schedule-edit", ph("mode", ruleId == null ? "Neu" : ruleId));
    }

    @Override
    protected void build() {
        frame();
        backButton(() -> gui.openSchedules(viewer));
        closeButton();

        ZoneId zone = gui.plugin().config().restartTimezone();
        String freqLabel = gui.config().label("freq-" + frequency.name().toLowerCase(Locale.ROOT));
        String timeStr = frequency == RestartScheduleStore.Frequency.HOURLY
                ? String.format(":%02d", minute)
                : String.format("%02d:%02d", hour, minute);

        set(10, Button.of(gui.item("sched-freq", Material.REPEATER, ph("freq", freqLabel)).build(), Perm.SCHEDULE, c -> {
            RestartScheduleStore.Frequency[] vals = RestartScheduleStore.Frequency.values();
            frequency = vals[(frequency.ordinal() + 1) % vals.length];
            if ((frequency == RestartScheduleStore.Frequency.WEEKLY || frequency == RestartScheduleStore.Frequency.MULTI_DAY) && days.isEmpty()) {
                days.add(DayOfWeek.MONDAY);
            }
            if (frequency == RestartScheduleStore.Frequency.WEEKLY && days.size() > 1) {
                days = EnumSet.of(days.iterator().next());
            }
            render();
        }));

        set(12, Button.of(gui.item("sched-time", Material.CLOCK, ph("time", timeStr), ph("zone", zone.getId())).build(),
                Perm.SCHEDULE, c -> askTime()));

        set(14, Button.of(gui.item("sched-label", Material.NAME_TAG,
                ph("label", label.isBlank() ? "-" : label)).build(), Perm.SCHEDULE, c -> askLabel()));

        set(16, Button.of(gui.item("sched-require-pending", Material.EXPERIENCE_BOTTLE,
                ph("state", gui.config().label(requirePending ? "yes-label" : "no-label"))).build(), Perm.SCHEDULE, c -> {
            requirePending = !requirePending;
            render();
        }));

        if (frequency == RestartScheduleStore.Frequency.WEEKLY || frequency == RestartScheduleStore.Frequency.MULTI_DAY) {
            DayOfWeek[] order = DayOfWeek.values();
            for (int i = 0; i < 7; i++) {
                DayOfWeek d = order[i];
                boolean on = days.contains(d);
                String dayName = d.getDisplayName(TextStyle.FULL, Locale.GERMAN);
                set(28 + i, Button.of(gui.item(on ? "sched-day-on" : "sched-day-off",
                        on ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                        ph("day", dayName)).build(), Perm.SCHEDULE, c -> toggleDay(d)));
            }
        }

        set(Slots.CONFIRM, Button.of(gui.item("sched-save", Material.LIME_CONCRETE).build(), Perm.SCHEDULE, c -> save()));

        if (ruleId != null) {
            set(Slots.EXTRA_LEFT, Button.of(gui.item("sched-toggle", Material.LEVER,
                    ph("state", enabled ? "aktiv" : "deaktiviert")).build(), Perm.SCHEDULE, c -> {
                enabled = !enabled;
                render();
            }));
            set(Slots.EXTRA_RIGHT, Button.of(gui.item("sched-delete", Material.RED_CONCRETE).build(), Perm.SCHEDULE, c -> {
                gui.plugin().restartSchedule().removeRule(ruleId);
                viewer.sendMessage(gui.plugin().messages().render("schedule.removed", ph("id", ruleId)));
                gui.openSchedules(viewer);
            }));
        }
    }

    private void toggleDay(DayOfWeek d) {
        if (frequency == RestartScheduleStore.Frequency.WEEKLY) {
            days = EnumSet.of(d);
        } else {
            if (days.contains(d)) {
                if (days.size() > 1) days.remove(d);
            } else {
                days.add(d);
            }
        }
        render();
    }

    private void askTime() {
        String promptLabel = gui.plugin().messages().plain(gui.config().text("text.schedule.time-prompt"));
        String initial = frequency == RestartScheduleStore.Frequency.HOURLY
                ? String.format("%02d", minute) : String.format("%02d:%02d", hour, minute);
        gui.ask(viewer, gui.simplePrompt(promptLabel, initial,
                in -> parseTimeInput(in, frequency) == null
                        ? Optional.of(gui.plugin().messages().render("schedule.invalid-time"))
                        : Optional.empty(),
                in -> {
                    int[] hm = parseTimeInput(in, frequency);
                    if (hm != null) {
                        this.hour = hm[0];
                        this.minute = hm[1];
                    }
                    open();
                },
                this::open));
    }

    private void askLabel() {
        String promptLabel = gui.plugin().messages().plain(gui.config().text("text.schedule.label-prompt"));
        String initial = label.isBlank() ? "Neustart" : label;
        gui.ask(viewer, gui.simplePrompt(promptLabel, initial,
                in -> Optional.empty(),
                in -> {
                    this.label = in == null ? "" : in.trim();
                    open();
                },
                this::open));
    }

    private void save() {
        if (!gui.plugin().config().restartsEnabled()) {
            viewer.sendMessage(gui.plugin().messages().render("schedule.disabled"));
            return;
        }
        Set<DayOfWeek> effectiveDays = (frequency == RestartScheduleStore.Frequency.WEEKLY || frequency == RestartScheduleStore.Frequency.MULTI_DAY)
                ? EnumSet.copyOf(days)
                : Set.of();
        ZoneId zone = gui.plugin().config().restartTimezone();
        try {
            RestartScheduleStore.ScheduleRule saved;
            if (ruleId == null) {
                saved = gui.plugin().restartSchedule().addRule(label, frequency, effectiveDays, hour, minute, requirePending);
            } else {
                saved = gui.plugin().restartSchedule().updateRule(ruleId, label, frequency, effectiveDays, hour, minute, enabled, requirePending);
            }
            String time = saved.frequency() == RestartScheduleStore.Frequency.HOURLY
                    ? String.format(":%02d", saved.minute()) : String.format("%02d:%02d", saved.hour(), saved.minute());
            viewer.sendMessage(gui.plugin().messages().render("schedule.added", ph("id", saved.id()),
                    ph("freq", saved.frequency().name()), ph("time", time), ph("zone", zone.getId())));
            gui.openSchedules(viewer);
        } catch (RestartScheduleStore.ScheduleConflictException e) {
            viewer.sendMessage(gui.plugin().messages().render("schedule.conflict", ph("reason", e.getMessage())));
            gui.config().sound(viewer, "error");
        }
    }

    static int[] parseTimeInput(String raw, RestartScheduleStore.Frequency freq) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim();
        if (freq == RestartScheduleStore.Frequency.HOURLY) {
            if (clean.startsWith(":")) clean = clean.substring(1);
            if (clean.contains(":")) {
                String[] parts = clean.split(":");
                if (parts.length != 2) return null;
                try {
                    int m = Integer.parseInt(parts[1].trim());
                    return (m >= 0 && m <= 59) ? new int[]{0, m} : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            try {
                int m = Integer.parseInt(clean);
                return (m >= 0 && m <= 59) ? new int[]{0, m} : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String[] parts = clean.split(":");
        if (parts.length != 2) return null;
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return new int[]{h, m};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
