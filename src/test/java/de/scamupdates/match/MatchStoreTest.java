package de.scamupdates.match;

import de.scamupdates.logging.ScamLog;
import de.scamupdates.model.ProjectRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MatchStoreTest {
    @TempDir Path dir;
    ScamLog log = new ScamLog(Logger.getLogger("test"), null);

    @Test
    void noneOfTheseExclusionPersistsAcrossRestarts() throws Exception {
        Path file = dir.resolve("matches.yml");
        new MatchStore(file, log).exclude("SomePlugin", MatchStore.Reason.NONE_OF_THESE);
        MatchStore reloaded = new MatchStore(file, log);
        assertEquals(MatchStore.Reason.NONE_OF_THESE, reloaded.exclusion("someplugin").orElseThrow().reason());
        assertTrue(reloaded.assignment("SomePlugin").isEmpty());
    }

    @Test
    void assignmentPersistsAndClearsExclusion() throws Exception {
        Path file = dir.resolve("matches.yml");
        MatchStore s = new MatchStore(file, log);
        s.exclude("Foo", MatchStore.Reason.MANUAL_COMMAND);
        s.assign("Foo", new ProjectRef("modrinth", "abc", "Foo", "https://modrinth.com/project/foo"));
        MatchStore reloaded = new MatchStore(file, log);
        assertTrue(reloaded.exclusion("Foo").isEmpty());
        assertEquals("modrinth:abc", reloaded.assignment("Foo").orElseThrow().key());
    }

    @Test
    void unmatchedPluginsAreNotSearchedAgainImmediately() throws Exception {
        MatchStore s = new MatchStore(dir.resolve("matches.yml"), log);
        assertTrue(s.shouldSearch("Foo", 24));
        s.markUnmatched("Foo");
        assertFalse(s.shouldSearch("Foo", 24));
        assertTrue(s.shouldSearch("Foo", 0));
        s.removeExclusion("Foo");
    }
}
