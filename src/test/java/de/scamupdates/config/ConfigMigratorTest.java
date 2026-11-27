package de.scamupdates.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigMigratorTest {

    private static final String DEFAULTS = """
            config-version: 2
            # Kommentar A
            a: 1
            # Kommentar B
            b: 2
            section:
              # innen
              x: 5
              list:
                - one
                - two
            plugins: {}
            """;

    @Test
    void addsMissingKeysKeepsUserValuesAndComments() throws Exception {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString(DEFAULTS);
        YamlConfiguration user = new YamlConfiguration();
        user.loadFromString("config-version: 1\n# mein Kommentar\na: 99\nplugins:\n  Foo:\n    source: modrinth\n");

        ConfigMigrator.Result r = ConfigMigrator.migrate(user, defaults);

        assertTrue(r.changed());
        assertEquals(1, r.fromVersion());
        assertEquals(2, r.toVersion());
        assertEquals(99, user.getInt("a"), "Benutzerwert darf nicht ueberschrieben werden");
        assertEquals(2, user.getInt("b"));
        assertEquals(5, user.getInt("section.x"));
        assertEquals(2, user.getStringList("section.list").size());
        assertEquals("modrinth", user.getString("plugins.Foo.source"), "manuelle Zuordnungen bleiben erhalten");
        assertEquals(2, user.getInt("config-version"));
        assertFalse(user.getComments("b").isEmpty(), "Kommentar des neuen Schluessels wurde mitkopiert");
        assertFalse(user.getComments("a").isEmpty(), "bestehender Kommentar bleibt erhalten");
        assertTrue(r.addedKeys().contains("b"));
        assertFalse(r.addedKeys().contains("a"));
    }

    @Test
    void versionChangeReplacesOldCommentsButNeverValues() throws Exception {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString("config-version: 2\n# Kurz.\na: 1\n");
        YamlConfiguration user = new YamlConfiguration();
        user.loadFromString("config-version: 1\n# Sehr langer alter Kommentar.\n# Noch eine Zeile.\na: 99\n");
        ConfigMigrator.migrate(user, defaults);
        assertEquals(99, user.getInt("a"));
        assertEquals(defaults.getComments("a"), user.getComments("a"));
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString(DEFAULTS);
        YamlConfiguration user = new YamlConfiguration();
        user.loadFromString("config-version: 1\n");
        ConfigMigrator.migrate(user, defaults);
        ConfigMigrator.Result second = ConfigMigrator.migrate(user, defaults);
        assertFalse(second.changed());
        assertTrue(second.addedKeys().isEmpty());
    }

    @Test
    void shippedDefaultConfigIsValidYamlWithRequiredKeys() throws Exception {
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(2, y.getInt("config-version"));
            assertEquals("anvil", y.getString("confirmations.method"));
            assertTrue(y.getBoolean("crafty.enabled"));
            assertNotNull(y.get("local-restart.command"));
            assertTrue(y.getBoolean("confirmations.enabled"), "Bestaetigungen sind standardmaessig aktiv");
            assertTrue(y.getBoolean("crafty.connection.verify-ssl"), "SSL-Pruefung ist standardmaessig aktiv");
            assertFalse(y.getBoolean("crafty.connection.allow-http"));
            assertEquals(CraftyConfig.TOKEN_PLACEHOLDER, y.getString("crafty.connection.api-token"));
            assertEquals(CraftyConfig.SERVER_ID_PLACEHOLDER, y.getString("crafty.connection.server-id"));
            assertNotNull(y.get("messages.error.missing-crafty"));
            assertNotNull(y.get("messages.error.no-internet"));
            assertNotNull(y.get("messages.download.finished"));
        }
    }

    @Test
    void freshDefaultConfigCountsAsIncompleteCraftySetup() throws Exception {
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            CraftyConfig cc = new CraftyConfig(y.getBoolean("crafty.enabled"), y.getString("crafty.connection.base-url"),
                    y.getString("crafty.connection.api-base-path"), y.getString("crafty.connection.api-token"),
                    y.getString("crafty.connection.server-id"), 10000, true, null, false, true);
            assertEquals(3, cc.missingSettings().size(), cc.missingSettings().toString());
        }
    }
}
