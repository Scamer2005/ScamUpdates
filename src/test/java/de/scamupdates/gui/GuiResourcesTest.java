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
                "menu-check", "menu-history", "nav-back", "nav-close", "nav-prev", "nav-next", "nav-info", "nav-cancel", "yes", "no",
                "yesno-question", "check-quick", "check-detailed", "check-progress", "check-progress-paper", "sum-updates", "sum-current",
                "sum-open", "sum-problems", "sum-excluded", "sum-paper", "sum-show-all", "sum-rerun", "results-update", "results-current",
                "results-open", "results-problem", "results-excluded", "results-filter", "results-detail", "results-empty", "history-ok",
                "history-warn", "history-failed", "history-info", "history-sort", "history-empty", "code-paper", "text-paper");
        for (String id : items) {
            assertTrue(g.isConfigurationSection("items." + id), "items." + id + " fehlt (YAML-Booleans yes/no muessen quotiert sein)");
            assertTrue(g.getString("items." + id + ".material", "").matches("[A-Z0-9_]+"), id + ": Material");
            assertFalse(g.getString("items." + id + ".name", "").isBlank(), id + ": Name");
        }
        for (String t : List.of("main", "check", "results", "history", "yesno")) assertNotNull(g.getString("titles." + t), t);
        for (String t : List.of("text.code.input-name", "text.code.cancelled", "text.chat.prompt", "text.chat.cancelled",
                "text.check.cooldown", "text.check.started", "text.check.cancel-requested", "text.check.cancel-question")) {
            assertNotNull(g.getString(t), t);
        }
        for (String l : List.of("filter-all", "filter-updates", "filter-problems", "mode-quick", "mode-detailed", "sort-newest",
                "sort-oldest", "sort-problems", "crafty-mode-crafty", "crafty-mode-local", "control-ready", "control-not-ready",
                "compat-tested", "compat-untested", "compat-too-old", "compat-unsupported-newer", "compat-unknown")) {
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
                "download.no-restart-warning", "confirm.question.download", "confirm.question.restart",
                "offline.notice", "offline.action-blocked", "crafty.connected", "crafty.degraded")) {
            assertNotNull(c.get("messages." + k), "messages." + k);
        }
        assertEquals(6, c.getInt("confirmations.code-length"));
        assertEquals("restart", c.getString("local-restart.command"));
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
