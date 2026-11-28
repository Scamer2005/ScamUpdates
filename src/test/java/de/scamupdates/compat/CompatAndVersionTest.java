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
        assertTrue(ServerVersion.of("26.3").compareTo(ServerVersion.of("26.2")) > 0);
        assertTrue(ServerVersion.of("26.2").compareTo(ServerVersion.of("26.1.2")) > 0);
        assertTrue(ServerVersion.of("26.1.2").compareTo(ServerVersion.of("26.1")) > 0);
        assertTrue(ServerVersion.of("27.1").compareTo(ServerVersion.of("26.3")) > 0);
        assertTrue(ServerVersion.parse("kaputt").isEmpty());
        assertTrue(ServerVersion.parse(null).isEmpty());
    }

    @Test
    void supportedFamiliesAre120x121xAnd26x() {
        for (String ver : List.of("1.20", "1.20.0", "1.20.4", "1.21.4", "1.21.11", "26.1", "26.1.2", "26.2", "26.3")) {
            CompatInfo info = CompatInfo.evaluate(ver, ver + "-1-x", List.of());
            assertEquals(CompatInfo.Status.UNTESTED, info.status(), "erwartet UNTESTED fuer " + ver);
            assertTrue(info.isSupported(), "erwartet isSupported() fuer " + ver);
        }

        assertEquals(CompatInfo.Status.TOO_OLD, CompatInfo.evaluate("1.19.4", "1.19.4-550-x", List.of()).status());
        assertFalse(CompatInfo.evaluate("1.19.4", "1.19.4-550-x", List.of()).isSupported());

        for (String future : List.of("1.22", "1.22.1", "2.0", "25.1", "27.0", "27.1", "28.0")) {
            CompatInfo info = CompatInfo.evaluate(future, future + "-1-x", List.of());
            assertEquals(CompatInfo.Status.UNSUPPORTED_NEWER, info.status(), "erwartet UNSUPPORTED_NEWER fuer " + future);
            assertFalse(info.isSupported(), "erwartet !isSupported() fuer " + future);
        }

        assertEquals(CompatInfo.Status.UNKNOWN, CompatInfo.evaluate("seltsam", "", List.of()).status());
        assertFalse(CompatInfo.isSupportedVersion(null));
    }

    @Test
    void testedOnlyWhenOperatorSaysSoInsideSupportedRange() {
        assertEquals(CompatInfo.Status.TESTED, CompatInfo.evaluate("1.21.11", FULL, List.of("1.21.11")).status());
        assertEquals(CompatInfo.Status.UNTESTED, CompatInfo.evaluate("1.21.11", FULL, List.of("1.21.4")).status());

        for (String v26 : List.of("26.1.2", "26.2", "26.3")) {
            assertEquals(CompatInfo.Status.TESTED,
                    CompatInfo.evaluate(v26, v26 + ".build.74-stable", List.of("1.20.4", "26.1.2", "26.2", "26.3")).status());
        }
        assertEquals(CompatInfo.Status.UNTESTED,
                CompatInfo.evaluate("26.3", "26.3.build.264-stable", List.of("26.2")).status());

        for (String future : List.of("1.22", "25.1", "27.1")) {
            assertEquals(CompatInfo.Status.UNSUPPORTED_NEWER,
                    CompatInfo.evaluate(future, future + "-3-x", List.of(future)).status(),
                    "ausserhalb des Supportbereichs hilft auch ein Eintrag nicht: " + future);
        }
    }

    @Test
    void paperBuildIsParsedFromLegacyAnd26xFormats() {
        assertEquals(69, CompatInfo.evaluate("1.21.11", FULL, List.of()).paperBuild());
        assertEquals(74, CompatInfo.evaluate("26.1.2", "26.1.2.build.74-stable (MC: 26.1.2)", List.of()).paperBuild());
        assertEquals(264, CompatInfo.evaluate("26.3", "26.3.build.264-stable (MC: 26.3)", List.of()).paperBuild());
        assertEquals(-1, CompatInfo.evaluate("1.21.11", "irgendwas", List.of()).paperBuild());
        assertTrue(CompatInfo.evaluate("1.21.11", FULL, List.of()).isSupported());
        assertTrue(CompatInfo.evaluate("26.1.2", "26.1.2.build.74-stable", List.of()).isSupported());
        assertTrue(CompatInfo.evaluate("26.3", "26.3.build.264-stable", List.of()).isSupported());
    }
}
