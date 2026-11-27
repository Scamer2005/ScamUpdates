package de.scamupdates.ops;

import de.scamupdates.logging.ScamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OperationRegistryTest {
    @TempDir Path dir;
    ScamLog log = new ScamLog(Logger.getLogger("test"), null);

    @Test
    void sameTypeAndKeyCannotRunTwice() {
        OperationRegistry r = new OperationRegistry(dir.resolve("ops.yml"), log);
        Operation first = r.begin(OpType.DOWNLOAD, "plugins", "admin").orElseThrow();
        assertTrue(r.begin(OpType.DOWNLOAD, "plugins", "other").isEmpty());
        assertTrue(r.begin(OpType.DOWNLOAD, "PLUGINS", "other").isEmpty(), "Schluessel ist case-insensitiv");
        assertTrue(r.begin(OpType.DOWNLOAD, "Foo", "other").isPresent(), "anderer Schluessel (anderes Plugin) ist erlaubt");
        r.finish(first, OpStatus.SUCCEEDED, "fertig");
        assertTrue(r.begin(OpType.DOWNLOAD, "plugins", "admin").isPresent());
    }

    @Test
    void progressAndFinishAreTracked() {
        OperationRegistry r = new OperationRegistry(dir.resolve("ops.yml"), log);
        Operation op = r.begin(OpType.CHECK, "check", "admin").orElseThrow();
        assertEquals(8, op.id().length());
        r.progress(op, 42, "Foo");
        assertEquals(42, op.percent());
        assertEquals("Foo", op.message());
        assertTrue(r.active(OpType.CHECK).isPresent());
        r.finish(op, OpStatus.SUCCEEDED, "ok");
        assertEquals(100, op.percent());
        assertFalse(op.isActive());
        assertTrue(r.active(OpType.CHECK).isEmpty());
        r.finish(op, OpStatus.FAILED, "darf nicht mehr aendern");
        assertEquals(OpStatus.SUCCEEDED, op.status());
    }

    @Test
    void runningOperationsBecomeInterruptedAfterRestartAndAreNeverResumed() {
        Path f = dir.resolve("ops.yml");
        OperationRegistry before = new OperationRegistry(f, log);
        Operation running = before.begin(OpType.SERVER_UPDATE, "server", "admin").orElseThrow();
        Operation done = before.begin(OpType.CHECK, "check", "admin").orElseThrow();
        before.finish(done, OpStatus.SUCCEEDED, "ok");

        OperationRegistry after = new OperationRegistry(f, log);
        assertEquals(1, after.markInterrupted());
        assertEquals(0, after.markInterrupted());
        assertTrue(after.active(OpType.SERVER_UPDATE).isEmpty());
        assertEquals(OpStatus.INTERRUPTED, after.recent().stream().filter(o -> o.id().equals(running.id())).findFirst().orElseThrow().status());
        assertTrue(after.begin(OpType.SERVER_UPDATE, "server", "admin").isPresent(), "nach Unterbrechung ist ein neuer Vorgang moeglich");
    }
}
