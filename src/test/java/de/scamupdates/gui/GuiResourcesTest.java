package de.scamupdates.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuiResourcesTest {

    private static YamlConfiguration load(String res) throws Exception {
        try (var in = GuiResourcesTest.class.getResourceAsStream(res)) {
            assertNotNull(in, res);
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return y;
        }
    }

    @Test
    void guiYmlContainsEveryItemTextTitleAndLabelUsedByTheCode() throws Exception {
        YamlConfiguration g = load("/gui.yml");
        List<String> items = List.of("status-minecraft", "status-paper", "status-plugins", "status-updates", "status-crafty", "status-state",
                "status-restart", "menu-check", "menu-single", "menu-queue", "menu-schedule", "menu-onetime", "menu-history",
                "nav-back", "nav-close", "nav-prev", "nav-next", "nav-info", "nav-cancel", "yes", "no", "yesno-question",
                "check-quick", "check-detailed", "check-progress", "check-progress-paper", "sum-updates", "sum-current",
                "sum-open", "sum-problems", "sum-excluded", "sum-paper", "sum-show-all", "sum-rerun", "results-update", "results-current",
                "results-open", "results-problem", "results-excluded", "results-filter", "results-detail", "results-empty", "history-ok",
                "history-warn", "history-failed", "history-info", "history-sort", "history-empty", "code-paper", "text-paper",
                "lib-update", "lib-current", "lib-open", "lib-problem", "lib-excluded", "lib-pending",
                "lib-search", "lib-filter", "lib-sort", "lib-empty",
                "detail-plugin", "detail-update", "detail-source", "detail-compat", "detail-backup", "detail-history",
                "detail-check", "detail-download", "detail-match", "detail-queue-add", "detail-queue-remove",
                "detail-exclude", "detail-include", "detail-restore",
                "match-candidate", "match-none", "match-empty",
                "queue-entry-ready", "queue-entry-ambiguous", "queue-entry-waiting", "queue-entry-snoozed", "queue-entry-problem",
                "queue-empty", "queue-check-all", "queue-download-all",
                "queue-action-update", "queue-action-choose", "queue-action-later", "queue-action-dismiss", "queue-action-detail",
                "sched-rule-on", "sched-rule-off", "sched-empty", "sched-add", "sched-readiness", "sched-active",
                "sched-freq", "sched-time", "sched-label", "sched-require-pending", "sched-save", "sched-toggle", "sched-delete",
                "sched-day-on", "sched-day-off",
                "onetime-status", "onetime-cancel", "onetime-preset-immediate", "onetime-preset",
                "onetime-custom-hours", "onetime-custom-minutes", "onetime-custom-seconds", "onetime-custom-reason",
                "onetime-custom-start", "onetime-readiness");
        for (String id : items) {
            assertTrue(g.isConfigurationSection("items." + id), "items." + id + " fehlt (YAML-Booleans yes/no muessen quotiert sein)");
            assertTrue(g.getString("items." + id + ".material", "").matches("[A-Z0-9_]+"), id + ": Material");
            assertFalse(g.getString("items." + id + ".name", "").isBlank(), id + ": Name");
        }
        for (String t : List.of("main", "check", "results", "history", "yesno",
                "library", "detail", "match", "queue", "queue-entry", "schedules", "schedule-edit", "onetime")) {
            assertNotNull(g.getString("titles." + t), t);
        }
        for (String t : List.of("text.code.input-name", "text.code.cancelled", "text.chat.prompt", "text.chat.cancelled",
                "text.check.cooldown", "text.check.started", "text.check.cancel-requested", "text.check.cancel-question",
                "text.library.search-prompt", "text.schedule.time-prompt", "text.schedule.label-prompt",
                "text.onetime.hours-prompt", "text.onetime.minutes-prompt", "text.onetime.seconds-prompt", "text.onetime.reason-prompt")) {
            assertNotNull(g.getString(t), t);
        }
        for (String l : List.of("filter-all", "filter-updates", "filter-problems", "mode-quick", "mode-detailed", "sort-newest",
                "sort-oldest", "sort-problems", "crafty-mode-crafty", "crafty-mode-local", "control-ready", "control-not-ready",
                "compat-tested", "compat-untested", "compat-too-old", "compat-unsupported-newer", "compat-unknown",
                "lib-filter-all", "lib-filter-updates", "lib-filter-current", "lib-filter-problems", "lib-filter-excluded",
                "lib-filter-backup", "lib-sort-name", "lib-sort-status", "lib-sort-updates", "lib-sort-backup",
                "freq-hourly", "freq-daily", "freq-weekly", "freq-multi_day", "yes-label", "no-label")) {
            assertNotNull(g.getString("design-labels." + l), l);
        }
        assertEquals("anvil", g.getString("general.input-method"));
        assertEquals(1, g.getInt("config-version"));
    }

    @Test
    void configYmlContainsNewMessageKeys() throws Exception {
        YamlConfiguration c = load("/config.yml");
        for (String k : List.of("gui.disabled", "gui.no-permission-item", "confirm.code-info", "confirm.code-wrong", "confirm.code-locked",
                "history.header", "history.entry", "history.empty", "restart.requested-local", "restart.mechanism-missing",
                "restart.warning", "restart.countdown-cancelled", "restart.scheduled-failed",
                "download.no-restart-warning", "confirm.question.download", "confirm.question.restart",
                "offline.notice", "offline.action-blocked", "crafty.connected", "crafty.degraded",
                "queue.added", "queue.already", "queue.removed", "queue.snoozed", "queue.dismissed",
                "queue.empty", "queue.header", "queue.entry", "queue.notify-ready", "queue.notify-ambiguous",
                "single.checking", "single.up-to-date", "single.update-available", "single.no-direct-download",
                "single.no-update", "single.excluded", "single.pending-match",
                "schedule.disabled", "schedule.header", "schedule.empty", "schedule.rule", "schedule.added",
                "schedule.removed", "schedule.toggled", "schedule.not-found", "schedule.conflict", "schedule.invalid-time",
                "schedule.onetime-scheduled", "schedule.onetime-cancelled", "schedule.onetime-none", "schedule.onetime-active")) {
            assertNotNull(c.get("messages." + k), "messages." + k);
        }
        for (String b : List.of("queue-update-now", "queue-later", "queue-dismiss", "queue-choose")) {
            assertNotNull(c.getString("buttons." + b + ".text"), "buttons." + b);
        }
        assertEquals(6, c.getInt("confirmations.code-length"));
        assertEquals("restart", c.getString("local-restart.command"));
        assertEquals("Europe/Berlin", c.getString("restarts.timezone"));
        assertEquals(60, c.getInt("queue.snooze-minutes"));
        assertFalse(c.getIntegerList("restarts.warning-seconds").isEmpty());
    }

    @Test
    void headTexturesAreRestrictedToMojangTextureHost() {
        String good = "http://textures.minecraft.net/texture/abc123";
        assertEquals(good, ItemBuilder.resolveUrl(good));
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + good + "\"}}}";
        assertEquals(good, ItemBuilder.resolveUrl(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
        String evil = "{\"textures\":{\"SKIN\":{\"url\":\"http://evil.example/tex.png\"}}}";
        assertNull(ItemBuilder.resolveUrl(Base64.getEncoder().encodeToString(evil.getBytes(StandardCharsets.UTF_8))));
        assertNull(ItemBuilder.resolveUrl("https://evil.example/texture/abc"));
        assertNull(ItemBuilder.resolveUrl("kein base64 !!"));
    }
}
