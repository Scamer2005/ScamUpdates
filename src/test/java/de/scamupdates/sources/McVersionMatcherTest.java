package de.scamupdates.sources;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McVersionMatcherTest {

    @Test
    void exactAndWildcardAndMinorMatches() {
        assertTrue(McVersionMatcher.matches("1.21.11", "1.21.11"));
        assertTrue(McVersionMatcher.matches("1.21.x", "1.21.11"));
        assertTrue(McVersionMatcher.matches("1.21", "1.21.11"));
        assertFalse(McVersionMatcher.matches("1.20.4", "1.21.11"));
        assertFalse(McVersionMatcher.matches("1.2", "1.21.11"));
    }

    @Test
    void ranges() {
        assertTrue(McVersionMatcher.matches("1.20-1.21.11", "1.21.4"));
        assertTrue(McVersionMatcher.matches("1.20-1.21", "1.21.11"));
        assertFalse(McVersionMatcher.matches("1.16-1.20.4", "1.21.11"));
        assertFalse(McVersionMatcher.matches("1.22-1.23", "1.21.11"));
    }

    @Test
    void anyMatches() {
        assertTrue(McVersionMatcher.anyMatches(List.of("1.19", "1.21.11"), "1.21.11"));
        assertFalse(McVersionMatcher.anyMatches(List.of(), "1.21.11"));
    }

    @Test
    void supportsPaper26xExactWildcardsAndRanges() {
        assertTrue(McVersionMatcher.matches("26.1.2", "26.1.2"));
        assertTrue(McVersionMatcher.matches("26.1.x", "26.1.2"));
        assertTrue(McVersionMatcher.matches("26.x", "26.2"));
        assertTrue(McVersionMatcher.matches("26.x", "26.3"));
        assertTrue(McVersionMatcher.matches("26.1-26.3", "26.2"));
        assertTrue(McVersionMatcher.matches("1.21-26.3", "26.1.2"));
        assertTrue(McVersionMatcher.anyMatches(List.of("1.21.11", "26.1.2", "26.3"), "26.3"));

        assertFalse(McVersionMatcher.matches("1.20.4", "26.1.2"));
        assertFalse(McVersionMatcher.matches("1.20-1.21.11", "26.1.2"));
        assertFalse(McVersionMatcher.matches("26.1-26.2", "26.3"));
        assertFalse(McVersionMatcher.matches("27.1", "26.3"));
    }

    @Test
    void nameMatcherNormalizes() {
        assertTrue(NameMatcher.same("World-Edit", "worldedit"));
        assertTrue(NameMatcher.same("LuckPerms Plugin", "LuckPerms"));
        assertFalse(NameMatcher.same("WorldEdit", "WorldGuard"));
        assertFalse(NameMatcher.same("", ""));
    }
}
