package de.scamupdates.backup;

import de.scamupdates.TestJars;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.update.Stager;
import de.scamupdates.utils.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BackupAndStagingTest {
    @TempDir Path dir;
    Path plugins, updateDir, backupDir, installed;
    BackupManager backups;
    Stager stager;
    ScamLog log = new ScamLog(Logger.getLogger("test"), null);

    @BeforeEach
    void setUp() throws Exception {
        plugins = dir.resolve("plugins");
        updateDir = plugins.resolve("update");
        backupDir = plugins.resolve("update-backup");
        installed = TestJars.plugin(plugins.resolve("Foo.jar"), "Foo", "1.0", "v1");
        backups = new BackupManager(backupDir, log);
        stager = new Stager(updateDir, backups);
    }

    private Path newVersion(String version) throws IOException {
        return TestJars.plugin(dir.resolve("tmp-" + version + ".jar"), "Foo", version, "payload-" + version);
    }

    @Test
    void stageCreatesBackupAndUpdateFileWithInstalledName() throws Exception {
        String oldHash = Hashing.sha256(installed);
        Path fresh = newVersion("2.0");
        String freshHash = Hashing.sha256(fresh);

        Stager.Result r = stager.stage("Foo", "1.0", installed, fresh);

        assertEquals("Foo.jar", r.stagedFile().getFileName().toString(), "Paper uebernimmt nur gleichnamige Dateien");
        assertEquals(freshHash, Hashing.sha256(r.stagedFile()));
        assertEquals(oldHash, Hashing.sha256(installed), "installierte JAR bleibt unveraendert");
        BackupManager.Entry e = backups.get("foo").orElseThrow();
        assertEquals("1.0", e.version());
        assertEquals(oldHash, Hashing.sha256(backupDir.resolve("Foo.jar")));
        assertTrue(backups.verify(e));
        assertFalse(Files.exists(fresh), "validierte Temp-Datei wurde verschoben");
    }

    @Test
    void secondUpdateReplacesBackupKeepingOnlyOne() throws Exception {
        stager.stage("Foo", "1.0", installed, newVersion("2.0"));
        // Server uebernimmt das Update (simuliert): installierte JAR ist jetzt 2.0
        Files.copy(updateDir.resolve("Foo.jar"), installed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.delete(updateDir.resolve("Foo.jar"));
        String v2Hash = Hashing.sha256(installed);

        stager.stage("Foo", "2.0", installed, newVersion("3.0"));

        assertEquals(1, backups.list().size());
        assertEquals("2.0", backups.get("Foo").orElseThrow().version());
        assertEquals(v2Hash, Hashing.sha256(backupDir.resolve("Foo.jar")));
        try (Stream<Path> s = Files.list(backupDir)) {
            assertEquals(2, s.count(), "nur Backup-JAR + Metadaten, keine Temp-Reste");
        }
    }

    @Test
    void backupsSurviveRestart() throws Exception {
        stager.stage("Foo", "1.0", installed, newVersion("2.0"));
        BackupManager reloaded = new BackupManager(backupDir, log);
        assertEquals("1.0", reloaded.get("Foo").orElseThrow().version());
        assertTrue(reloaded.verify(reloaded.get("Foo").orElseThrow()));
    }

    @Test
    void failedStagingKeepsOldBackupAndInstalledJar() throws Exception {
        stager.stage("Foo", "1.0", installed, newVersion("2.0"));
        Files.copy(updateDir.resolve("Foo.jar"), installed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.delete(updateDir.resolve("Foo.jar"));
        String installedHash = Hashing.sha256(installed);
        String backupHashBefore = Hashing.sha256(backupDir.resolve("Foo.jar"));

        // Ziel blockieren: in update/ liegt ein nicht leeres Verzeichnis mit dem Namen der JAR
        Files.createDirectories(updateDir.resolve("Foo.jar"));
        Path blocker = Files.createFile(updateDir.resolve("Foo.jar").resolve("x"));
        Path third = newVersion("3.0");

        assertThrows(IOException.class, () -> stager.stage("Foo", "2.0", installed, third));

        assertEquals(installedHash, Hashing.sha256(installed));
        assertEquals(backupHashBefore, Hashing.sha256(backupDir.resolve("Foo.jar")), "altes Backup darf nicht verloren gehen");
        assertEquals("1.0", backups.get("Foo").orElseThrow().version());
        try (Stream<Path> s = Files.list(backupDir)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().contains(".part-")), "keine Backup-Temp-Reste");
        }
        assertTrue(Files.exists(blocker));
    }

    @Test
    void deleteRemovesOnlyRecordedBackupJars() throws Exception {
        stager.stage("Foo", "1.0", installed, newVersion("2.0"));
        Path foreign = Files.writeString(backupDir.resolve("fremd.txt"), "nicht anfassen");
        Path configDir = Files.createDirectories(plugins.resolve("Foo"));
        Path cfg = Files.writeString(configDir.resolve("config.yml"), "a: 1");

        assertTrue(backups.delete("Foo").isPresent());

        assertFalse(Files.exists(backupDir.resolve("Foo.jar")));
        assertTrue(Files.exists(foreign));
        assertTrue(Files.exists(cfg), "Config-Ordner werden nie angefasst");
        assertTrue(backups.get("Foo").isEmpty());
        assertTrue(backups.delete("Foo").isEmpty());
    }

    @Test
    void deleteAllRemovesAllRecordedBackups() throws Exception {
        Path bar = TestJars.plugin(plugins.resolve("Bar.jar"), "Bar", "1.0", "b");
        stager.stage("Foo", "1.0", installed, newVersion("2.0"));
        stager.stage("Bar", "1.0", bar, TestJars.plugin(dir.resolve("bar2.jar"), "Bar", "2.0", "b2"));
        assertEquals(2, backups.deleteAll());
        assertTrue(backups.list().isEmpty());
        assertFalse(Files.exists(backupDir.resolve("Foo.jar")));
        assertFalse(Files.exists(backupDir.resolve("Bar.jar")));
    }

    @Test
    void discardLeavesExistingBackupIntact() throws Exception {
        stager.stage("Foo", "1.0", installed, newVersion("2.0"));
        String before = Hashing.sha256(backupDir.resolve("Foo.jar"));
        BackupManager.Prepared p = backups.prepare("Foo", "9.9", installed);
        backups.discard(p);
        assertEquals(before, Hashing.sha256(backupDir.resolve("Foo.jar")));
        assertEquals("1.0", backups.get("Foo").orElseThrow().version());
    }

    @Test
    void unsafeFileNamesAreRejected() {
        assertThrows(IOException.class, () -> de.scamupdates.utils.AtomicFiles.requireInside(updateDir, "../evil.jar"));
        assertThrows(IOException.class, () -> de.scamupdates.utils.AtomicFiles.requireInside(updateDir, "a/b.jar"));
    }
}
