package de.scamupdates.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionComparatorTest {

    @Test
    void numericOrdering() {
        assertTrue(VersionComparator.isNewer("1.10.0", "1.9.9"));
        assertTrue(VersionComparator.isNewer("2.0", "1.99.99"));
        assertFalse(VersionComparator.isNewer("1.2.3", "1.2.3"));
        assertFalse(VersionComparator.isNewer("1.2", "1.2.0"));
        assertFalse(VersionComparator.isNewer("1.2.2", "1.2.3"));
    }

    @Test
    void leadingVAndBuildMetadataAreIgnored() {
        assertEquals(0, VersionComparator.compare("v1.2.3", "1.2.3"));
        assertEquals(0, VersionComparator.compare("1.2.3+build7", "1.2.3+build9"));
        assertTrue(VersionComparator.isNewer("v2.0.0", "1.9.0"));
    }

    @Test
    void preReleasesAreOlderThanRelease() {
        assertTrue(VersionComparator.isNewer("1.0.0", "1.0.0-rc1"));
        assertTrue(VersionComparator.isNewer("1.0.0-rc1", "1.0.0-beta2"));
        assertTrue(VersionComparator.isNewer("1.0.0-beta1", "1.0.0-alpha3"));
        assertTrue(VersionComparator.isNewer("1.0.0-SNAPSHOT", "0.9.9"));
        assertFalse(VersionComparator.isNewer("1.0.0-SNAPSHOT", "1.0.0"));
        assertTrue(VersionComparator.isNewer("1.0.1", "1.0.0-rc1"));
    }

    @Test
    void unparsableVersionsAreNotOfferedByDefault() {
        assertFalse(VersionComparator.isNewer("latest", "dev"));
        assertTrue(VersionComparator.isNewer("latest", "dev", true));
        assertFalse(VersionComparator.isNewer("same", "SAME", true));
    }

    @Test
    void buildNumbersCountAsNewer() {
        assertTrue(VersionComparator.isNewer("1.0.0-b12", "1.0.0"));
        assertTrue(VersionComparator.isNewer("1.0.0-b13", "1.0.0-b12"));
    }
}
