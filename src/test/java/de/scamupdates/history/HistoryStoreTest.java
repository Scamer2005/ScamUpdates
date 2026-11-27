package de.scamupdates.history;

import de.scamupdates.logging.ScamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class HistoryStoreTest {
    @TempDir Path dir;
    ScamLog log = new ScamLog(Logger.getLogger("test"), null);

    private HistoryEntry at(long t, String action, String result) {
        return new HistoryEntry(t, action, result, "admin", "1.21.11", "msg", List.of());
    }

    @Test
    void appendAndListRoundTripWithDetails() {
        HistoryStore h = new HistoryStore(dir.resolve("h.jsonl"), log, 90, 100);
        h.append(HistoryEntry.of("plugin-update", "OK", "Steve", "1 vorgemerkt")
                .withDetails(List.of(new HistoryEntry.Detail("Foo", "1.0", "2.0", "OK"))).withMcTarget("1.21.11"));
        List<HistoryEntry> l = h.list(HistoryStore.Sort.NEWEST);
        assertEquals(1, l.size());
        assertEquals("Foo", l.get(0).details().get(0).plugin());
        assertEquals("1.21.11", l.get(0).mcTarget());
        assertEquals("Steve", l.get(0).actor());
    }

    @Test
    void secretsNeverReachTheFile() throws Exception {
        log.registerSecret("topsecret-token-123");
        HistoryStore h = new HistoryStore(dir.resolve("h.jsonl"), log, 90, 100);
        h.append(HistoryEntry.of("restart", "FAILED", "admin", "Fehler mit Bearer topsecret-token-123 aufgetreten")
                .withDetails(List.of(new HistoryEntry.Detail("Foo", "1", "2", "FEHLER: topsecret-token-123"))));
        String raw = Files.readString(dir.resolve("h.jsonl"));
        assertFalse(raw.contains("topsecret-token-123"));
        assertTrue(raw.contains("***"));
    }

    @Test
    void sortingModes() {
        HistoryStore h = new HistoryStore(dir.resolve("h.jsonl"), log, 0, 100);
        h.append(at(1000, "a", "OK"));
        h.append(at(2000, "b", "FAILED"));
        h.append(at(3000, "c", "WARN"));
        assertEquals("c", h.list(HistoryStore.Sort.NEWEST).get(0).action());
        assertEquals("a", h.list(HistoryStore.Sort.OLDEST).get(0).action());
        assertEquals("b", h.list(HistoryStore.Sort.PROBLEMS_FIRST).get(0).action());
        assertEquals("c", h.list(HistoryStore.Sort.PROBLEMS_FIRST).get(1).action());
    }

    @Test
    void pruneRemovesOldAndExcessEntries() {
        long now = 100L * 86_400_000L;
        HistoryStore h = new HistoryStore(dir.resolve("h.jsonl"), log, 30, 100);
        h.append(at(now - 40L * 86_400_000L, "alt", "OK"));
        h.append(at(now - 1000, "neu", "OK"));
        assertEquals(1, h.prune(now));
        assertEquals(List.of("neu"), h.list(HistoryStore.Sort.NEWEST).stream().map(HistoryEntry::action).toList());

        HistoryStore limited = new HistoryStore(dir.resolve("l.jsonl"), log, 0, 10);
        for (int i = 0; i < 15; i++) limited.append(at(1000L + i, "e" + i, "OK"));
        assertEquals(5, limited.prune(now));
        assertEquals("e14", limited.list(HistoryStore.Sort.NEWEST).get(0).action());
        assertEquals(10, limited.list(HistoryStore.Sort.NEWEST).size());
    }

    @Test
    void corruptLinesAreSkipped() throws Exception {
        Path f = dir.resolve("h.jsonl");
        HistoryStore h = new HistoryStore(f, log, 90, 100);
        h.append(at(5, "ok", "OK"));
        Files.writeString(f, "{kaputt\n", java.nio.file.StandardOpenOption.APPEND);
        h.append(at(6, "ok2", "OK"));
        assertEquals(2, h.list(HistoryStore.Sort.NEWEST).size());
    }
}
