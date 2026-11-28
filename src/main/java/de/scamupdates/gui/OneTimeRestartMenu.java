package de.scamupdates.gui;

import de.scamupdates.restart.RestartCoordinator;
import de.scamupdates.restart.RestartService;
import de.scamupdates.security.Perm;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.OptionalInt;

import static de.scamupdates.messaging.Messages.ph;

/**
 * Einmalige Neustarts mit Presets (Sofort, 10s, 1m, 5m, 10m, 30m, 1h), benutzerdefinierter
 * Zeit ueber Amboss-Eingabe (Stunden/Minuten/Sekunden) inkl. Live-Vorschau, Abbruch und Bereitschaftsanzeige.
 * Erfordert keinen separaten Bestaetigungscode; ein neuer Countdown ersetzt den vorherigen vollstaendig.
 */
final class OneTimeRestartMenu extends Menu {

    private int customHours = 0;
    private int customMinutes = 5;
    private int customSeconds = 0;
    private String customReason = "Manueller Neustart";
    private String lastFingerprint = "";

    OneTimeRestartMenu(GuiService gui, Player viewer) { super(gui, viewer); }

    @Override protected int rows() { return 6; }

    @Override protected Component title() { return gui.config().title("onetime"); }

    @Override
    protected void build() {
        frame();
        backButton(() -> gui.openMain(viewer));
        closeButton();

        long now = System.currentTimeMillis();
        ZoneId zone = gui.plugin().config().restartTimezone();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss").withZone(zone);

        Optional<RestartCoordinator.ActiveCountdown> active = gui.plugin().restartCoordinator() == null
                ? Optional.empty() : gui.plugin().restartCoordinator().activeCountdown();

        if (active.isPresent()) {
            RestartCoordinator.ActiveCountdown cd = active.get();
            set(13, gui.item("onetime-status", Material.CLOCK,
                    ph("state", "aktiv"),
                    ph("remaining", RestartCoordinator.formatDuration(cd.remainingSeconds(now))),
                    ph("target", fmt.format(Instant.ofEpochMilli(cd.targetMillis()))),
                    ph("zone", zone.getId()),
                    ph("reason", cd.reason().isBlank() ? "-" : cd.reason())).build());
            set(15, Button.of(gui.item("onetime-cancel", Material.BARRIER).build(), Perm.SCHEDULE, c -> {
                gui.plugin().restartCoordinator().cancelActive(viewer.getName());
                viewer.sendMessage(gui.plugin().messages().render("schedule.onetime-cancelled"));
                render();
            }));
        } else {
            set(13, gui.item("onetime-status", Material.CLOCK,
                    ph("state", "inaktiv"),
                    ph("remaining", "-"),
                    ph("target", "-"),
                    ph("zone", zone.getId()),
                    ph("reason", "-")).build());
        }

        // 7 Presets in Reihe 2 (Slots 19..25)
        RestartCoordinator.Preset[] presets = RestartCoordinator.Preset.values();
        for (int i = 0; i < presets.length; i++) {
            RestartCoordinator.Preset p = presets[i];
            if (p == RestartCoordinator.Preset.IMMEDIATE) {
                set(19 + i, Button.of(gui.item("onetime-preset-immediate", Material.REDSTONE_BLOCK).build(),
                        Perm.SCHEDULE, c -> triggerPreset(p)));
            } else {
                set(19 + i, Button.of(gui.item("onetime-preset", Material.CLOCK, ph("label", p.displayName)).build(),
                        Perm.SCHEDULE, c -> triggerPreset(p)));
            }
        }

        // Benutzerdefinierte Zeit in Reihe 3 (Slots 29..34) mit Live-Vorschau
        set(29, Button.of(gui.item("onetime-custom-hours", Material.PAPER, ph("hours", customHours)).build(),
                Perm.SCHEDULE, c -> askInt("text.onetime.hours-prompt", customHours, 0, 168, v -> customHours = v)));
        set(30, Button.of(gui.item("onetime-custom-minutes", Material.PAPER, ph("minutes", customMinutes)).build(),
                Perm.SCHEDULE, c -> askInt("text.onetime.minutes-prompt", customMinutes, 0, 59, v -> customMinutes = v)));
        set(31, Button.of(gui.item("onetime-custom-seconds", Material.PAPER, ph("seconds", customSeconds)).build(),
                Perm.SCHEDULE, c -> askInt("text.onetime.seconds-prompt", customSeconds, 0, 59, v -> customSeconds = v)));
        set(32, Button.of(gui.item("onetime-custom-reason", Material.NAME_TAG,
                ph("reason", customReason.isBlank() ? "-" : customReason)).build(), Perm.SCHEDULE, c -> askReason()));

        long customTotal = customTotalSeconds();
        String durationPreview = customTotal > 0 ? RestartCoordinator.formatDuration(customTotal) : "0 Sekunden (ungueltig)";
        String targetPreview = customTotal > 0 ? fmt.format(Instant.ofEpochMilli(now + customTotal * 1000L)) : "-";
        set(34, Button.of(gui.item("onetime-custom-start", Material.LIME_CONCRETE,
                ph("duration", durationPreview),
                ph("target", targetPreview),
                ph("zone", zone.getId())).build(), Perm.SCHEDULE, c -> startCustom()));

        RestartService rs = gui.plugin().restart();
        RestartService.Readiness rd = rs == null
                ? new RestartService.Readiness(false, RestartService.RestartMode.UNAVAILABLE, "Service inaktiv")
                : rs.readiness();
        set(Slots.EXTRA_RIGHT, gui.item("onetime-readiness", Material.COMPARATOR,
                ph("mode", rd.mode().name()),
                ph("ready", gui.config().label(rd.ready() ? "yes-label" : "no-label")),
                ph("detail", rd.detail())).build());

        lastFingerprint = fingerprint(now);
    }

    private long customTotalSeconds() {
        return customHours * 3600L + customMinutes * 60L + customSeconds;
    }

    private void triggerPreset(RestartCoordinator.Preset p) {
        if (!gui.plugin().config().restartsEnabled()) {
            viewer.sendMessage(gui.plugin().messages().render("schedule.disabled"));
            return;
        }
        long now = System.currentTimeMillis();
        if (p == RestartCoordinator.Preset.IMMEDIATE) {
            viewer.closeInventory();
            gui.plugin().restartCoordinator().schedulePreset(p, viewer.getName(), "Sofortiger Neustart", now)
                    .whenComplete((out, err) -> gui.plugin().runMain(() -> {
                        if (!viewer.isOnline()) return;
                        if (err != null) {
                            viewer.sendMessage(gui.plugin().messages().render("restart.failed",
                                    ph("reason", gui.plugin().log().redact(String.valueOf(err.getMessage())))));
                            gui.config().sound(viewer, "error");
                            return;
                        }
                        if (out == null) return;
                        switch (out.result()) {
                            case REQUESTED -> {
                                viewer.sendMessage(gui.plugin().messages().render("restart.requested"));
                                gui.config().sound(viewer, "success");
                            }
                            case REQUESTED_LOCAL -> {
                                viewer.sendMessage(gui.plugin().messages().render("restart.requested-local"));
                                gui.config().sound(viewer, "success");
                            }
                            case BUSY -> {
                                viewer.sendMessage(gui.plugin().messages().render("busy",
                                        ph("operation", out.details().isEmpty() ? "restart" : out.details().get(0))));
                                gui.config().sound(viewer, "error");
                            }
                            case PREFLIGHT_FAILED -> {
                                viewer.sendMessage(gui.plugin().messages().render("restart.preflight-failed",
                                        ph("problems", String.join("\n", out.details()))));
                                gui.config().sound(viewer, "error");
                            }
                            case DISABLED -> {
                                viewer.sendMessage(gui.plugin().messages().render("restart.disabled"));
                                gui.config().sound(viewer, "error");
                            }
                            case NO_PENDING -> viewer.sendMessage(gui.plugin().messages().render("restart.no-pending"));
                            case FAILED -> {
                                viewer.sendMessage(gui.plugin().messages().render("restart.failed",
                                        ph("reason", String.join("; ", out.details()))));
                                gui.config().sound(viewer, "error");
                            }
                        }
                    }));
            return;
        }
        try {
            gui.plugin().restartCoordinator().schedulePreset(p, viewer.getName(), p.displayName, now);
        } catch (IllegalStateException e) {
            viewer.sendMessage(gui.plugin().messages().render("busy", ph("operation", "Neustart")));
            gui.config().sound(viewer, "error");
            return;
        }
        ZoneId zone = gui.plugin().config().restartTimezone();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss").withZone(zone);
        viewer.sendMessage(gui.plugin().messages().render("schedule.onetime-scheduled",
                ph("duration", p.displayName),
                ph("at", fmt.format(Instant.ofEpochMilli(now + p.seconds * 1000L))),
                ph("zone", zone.getId())));
        render();
    }

    private void startCustom() {
        if (!gui.plugin().config().restartsEnabled()) {
            viewer.sendMessage(gui.plugin().messages().render("schedule.disabled"));
            return;
        }
        long total = customTotalSeconds();
        if (total <= 0) {
            viewer.sendMessage(gui.plugin().messages().render("schedule.invalid-time"));
            gui.config().sound(viewer, "error");
            return;
        }
        long now = System.currentTimeMillis();
        RestartCoordinator.ActiveCountdown cd;
        try {
            cd = gui.plugin().restartCoordinator().scheduleCustom(
                    customHours, customMinutes, customSeconds, viewer.getName(), customReason, now);
        } catch (IllegalStateException e) {
            viewer.sendMessage(gui.plugin().messages().render("busy", ph("operation", "Neustart")));
            gui.config().sound(viewer, "error");
            return;
        }
        ZoneId zone = gui.plugin().config().restartTimezone();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss").withZone(zone);
        viewer.sendMessage(gui.plugin().messages().render("schedule.onetime-scheduled",
                ph("duration", RestartCoordinator.formatDuration(total)),
                ph("at", fmt.format(Instant.ofEpochMilli(cd.targetMillis()))),
                ph("zone", zone.getId())));
        render();
    }

    private void askInt(String textKey, int current, int min, int max, java.util.function.IntConsumer setter) {
        String label = gui.plugin().messages().plain(gui.config().text(textKey));
        gui.ask(viewer, gui.simplePrompt(label, String.valueOf(current),
                in -> parseIntRange(in, min, max).isEmpty()
                        ? Optional.of(gui.plugin().messages().render("schedule.invalid-time"))
                        : Optional.empty(),
                in -> {
                    parseIntRange(in, min, max).ifPresent(setter);
                    open();
                },
                this::open));
    }

    private void askReason() {
        String label = gui.plugin().messages().plain(gui.config().text("text.onetime.reason-prompt"));
        String initial = customReason.isBlank() ? "Neustart" : customReason;
        gui.ask(viewer, gui.simplePrompt(label, initial,
                in -> Optional.empty(),
                in -> {
                    this.customReason = in == null ? "" : in.trim();
                    open();
                },
                this::open));
    }

    static OptionalInt parseIntRange(String raw, int min, int max) {
        if (raw == null || raw.isBlank()) return OptionalInt.empty();
        try {
            int val = Integer.parseInt(raw.trim());
            return (val >= min && val <= max) ? OptionalInt.of(val) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private String fingerprint(long now) {
        String cd = gui.plugin().restartCoordinator() == null ? "-"
                : gui.plugin().restartCoordinator().activeCountdown()
                        .map(c -> c.id() + ":" + c.remainingSeconds(now)).orElse("-");
        return cd + "|" + customHours + ":" + customMinutes + ":" + customSeconds + ":" + (now / 1000L);
    }

    @Override
    protected boolean needsRender() {
        return !fingerprint(System.currentTimeMillis()).equals(lastFingerprint);
    }
}
