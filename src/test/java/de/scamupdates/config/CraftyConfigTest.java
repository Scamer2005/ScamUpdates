package de.scamupdates.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CraftyConfigTest {

    private static CraftyConfig cfg(boolean enabled, String url, String api, String token, String id) {
        return new CraftyConfig(enabled, url, api, token, id, 10000, true, null, false, true);
    }

    @Test
    void completeConfigHasNoMissingSettings() {
        CraftyConfig c = cfg(true, "https://crafty.local:8443", "/api/v2", "abcdef123456", "1a2b3c4d-0000-1111-2222-333344445555");
        assertTrue(c.missingSettings().isEmpty());
        assertTrue(c.formatProblems().isEmpty());
    }

    @Test
    void eachMissingValueIsReported() {
        assertEquals(1, cfg(true, "", "/api/v2", "abcdef123456", "id1").missingSettings().size());
        assertEquals(1, cfg(true, "https://c", "", "abcdef123456", "id1").missingSettings().size());
        assertEquals(1, cfg(true, "https://c", "/api/v2", "", "id1").missingSettings().size());
        assertEquals(1, cfg(true, "https://c", "/api/v2", CraftyConfig.TOKEN_PLACEHOLDER, "id1").missingSettings().size());
        assertEquals(1, cfg(true, "https://c", "/api/v2", "abcdef123456", CraftyConfig.SERVER_ID_PLACEHOLDER).missingSettings().size());
        assertEquals(0, cfg(false, "https://c", "/api/v2", "abcdef123456", "id1").missingSettings().size(), "Crafty deaktiviert: keine Pflichtangaben");
        assertEquals(0, cfg(false, "", "", "", "").missingSettings().size());
        assertTrue(cfg(false, "", "", "", "").formatProblems().isEmpty());
        assertEquals(4, cfg(true, "", "", "", "").missingSettings().size());
    }

    @Test
    void exampleUrlCountsAsMissing() {
        assertFalse(cfg(true, "https://crafty.example.com", "/api/v2", "abcdef123456", "id1").missingSettings().isEmpty());
    }

    @Test
    void formatProblemsCoverUnsafeValues() {
        assertFalse(cfg(true, "crafty.local", "/api/v2", "abcdef123456", "id1").formatProblems().isEmpty());
        assertFalse(cfg(true, "http://crafty.local", "/api/v2", "abcdef123456", "id1").formatProblems().isEmpty());
        assertFalse(cfg(true, "https://crafty.local", "/api/v2", "abcdef123456", "../etc/passwd").formatProblems().isEmpty());
        assertFalse(cfg(true, "https://crafty.local", "/api/v2", "abcdef123456", "a/b").formatProblems().isEmpty());
    }
}
