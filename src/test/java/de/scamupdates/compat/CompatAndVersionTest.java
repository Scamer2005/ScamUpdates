package de.scamupdates.compat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompatAndVersionTest {
    private static final String FULL = "1.21.11-69-abcdef0 (MC: 1.21.11)";

    @Test
    void parsesAndComparesVersions() {
        assertEquals("1.21.11", ServerVersion.of(FULL).toString());
        assertTrue(ServerVersion.of("1.21.11").compareTo(ServerVersion.of("1.21.2")) > 0);
        assertEquals(ServerVersion.of("1.20"), ServerVersion.of("1.20.0"));
        assertEquals(ServerVersion.of("1.20").hashCode(), ServerVersion.of("1.20.0").hashCode());
        assertTrue(ServerVersion.of("26.1").isNewerThan("1.21.11"), "neues jahresbasiertes Schema");
        assertTrue(ServerVersion.parse("kaputt").isEmpty());
        assertTrue(ServerVersion.parse(null).isEmpty());
    }

    @Test
    void supportedRangeIs120To121x() {
        assertEquals(CompatInfo.Status.UNTESTED, CompatInfo.evaluate("1.20", "1.20-1-x", List.of()).status());
        assertEquals(CompatInfo.Status.UNTESTED, CompatInfo.evaluate("1.21.11", FULL, List.of()).status());
        assertEquals(CompatInfo.Status.UNTESTED, CompatInfo.evaluate("1.21.99", "1.21.99-1-x", List.of()).status());
        assertEquals(CompatInfo.Status.TOO_OLD, CompatInfo.evaluate("1.19.4", "1.19.4-550-x", List.of()).status());
        assertEquals(CompatInfo.Status.UNSUPPORTED_NEWER, CompatInfo.evaluate("1.22", "1.22-1-x", List.of()).status());
        assertEquals(CompatInfo.Status.UNSUPPORTED_NEWER, CompatInfo.evaluate("26.1", "26.1-3-x", List.of()).status());
        assertEquals(CompatInfo.Status.UNKNOWN, CompatInfo.evaluate("seltsam", "", List.of()).status());
    }

    @Test
    void testedOnlyWhenOperatorSaysSo() {
        assertEquals(CompatInfo.Status.TESTED, CompatInfo.evaluate("1.21.11", FULL, List.of("1.21.11")).status());
        assertEquals(CompatInfo.Status.UNTESTED, CompatInfo.evaluate("1.21.11", FULL, List.of("1.21.4")).status());
        assertEquals(CompatInfo.Status.UNSUPPORTED_NEWER, CompatInfo.evaluate("26.1", "26.1-3-x", List.of("26.1")).status(),
                "ausserhalb des Supportbereichs hilft auch ein Eintrag nicht");
    }

    @Test
    void paperBuildIsParsedFromServerVersion() {
        assertEquals(69, CompatInfo.evaluate("1.21.11", FULL, List.of()).paperBuild());
        assertEquals(-1, CompatInfo.evaluate("1.21.11", "irgendwas", List.of()).paperBuild());
        assertTrue(CompatInfo.evaluate("1.21.11", FULL, List.of()).isSupported());
        assertFalse(CompatInfo.evaluate("26.1", "26.1-3-x", List.of()).isSupported());
    }
}
