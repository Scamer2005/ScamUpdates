package de.scamupdates.rollback;

import de.scamupdates.TestJars;
import de.scamupdates.backup.BackupManager;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.security.OperationLock;
import de.scamupdates.state.PluginState;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.Stager;
import de.scamupdates.update.TransactionStore;
import de.scamupdates.utils.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RollbackServiceTest {
    @TempDir Path dir;
    Path plugins, updateDir, backupDir, installed;
    BackupManager backups;
    TransactionStore tx;
    RollbackService rollback;
    StateManager state = new StateManager();
    ScamLog log = new ScamLog(Logger.getLogger("test"), null);
    ExecutorService io = Executors.newSingleThreadExecutor();
    String v1Hash;

    @BeforeEach
    void setUp() throws Exception {
        plugins = dir.resolve("plugins");
        updateDir = plugins.resolve("update");
        backupDir = plugins.resolve("update-backup");
        backups = new BackupManager(backupDir, log);
        tx = new TransactionStore(plugins.resolve("ScamUpdates/transactions.yml"), log);
        Stager stager = new Stager(updateDir, backups);
        rollback = new RollbackService(state, backups, stager, tx, log, new OperationLock(), io, plugins,
                plugins.resolve("ScamUpdates/tmp"), () -> "1.21.11");
        state.set(PluginState.ACTIVE, List.of());

        // Zustand: installiert ist 2.0, Backup enthaelt 1.0
        Path v1 = TestJars.plugin(dir.resolve("src/Foo.jar"), "Foo", "1.0", "v1");
        v1Hash = Hashing.sha256(v1);
        backups.commit(backups.prepare("Foo", "1.0", v1));
        installed = TestJars.plugin(plugins.resolve("Foo.jar"), "Foo", "2.0", "v2");
    }

    private PluginInfo info() {
        return new PluginInfo("Foo", "2.0", List.of(), null, null, null, installed);
    }

    @Test
    void restoreStagesOldJarAndTurnsCurrentIntoNewBackup() throws Exception {
        String v2Hash = Hashing.sha256(installed);
        var plan = new RollbackService.Plan(backups.get("Foo").orElseThrow(), info());

        RollbackService.Item item = rollback.execute(plan);

        assertTrue(item.ok(), item.error());
        assertEquals(v1Hash, Hashing.sha256(updateDir.resolve("Foo.jar")));
        assertEquals(v2Hash, Hashing.sha256(installed), "laufende JAR wird nie direkt ueberschrieben");
        BackupManager.Entry now = backups.get("Foo").orElseThrow();
        assertEquals("2.0", now.version(), "bisherige Version ist wieder als Backup vorhanden");
        assertEquals(v2Hash, Hashing.sha256(backupDir.resolve("Foo.jar")));
        TransactionStore.Pending p = tx.get("Foo").orElseThrow();
        assertEquals(TransactionStore.Kind.RESTORE, p.kind());
        assertEquals("1.0", p.toVersion());
    }

    @Test
    void secondRestoreWhilePendingIsBlocked() throws Exception {
        var plan = new RollbackService.Plan(backups.get("Foo").orElseThrow(), info());
        assertTrue(rollback.execute(plan).ok());
        var again = rollback.execute(new RollbackService.Plan(backups.get("Foo").orElseThrow(), info()));
        assertFalse(again.ok());
        var planResult = rollback.plan(List.of(info()), List.of("Foo"));
        assertEquals(RollbackService.Problem.PENDING, planResult.failures().get(0).problem());
    }

    @Test
    void missingBackupIsReported() throws Exception {
        var res = rollback.plan(List.of(info()), List.of("Unknown"));
        assertTrue(res.plans().isEmpty());
        assertEquals(RollbackService.Problem.NO_BACKUP, res.failures().get(0).problem());
    }

    @Test
    void tamperedBackupIsRejectedAndNothingStaged() throws Exception {
        Files.writeString(backupDir.resolve("Foo.jar"), "manipuliert");
        var plan = new RollbackService.Plan(backups.get("Foo").orElseThrow(), info());
        var item = rollback.execute(plan);
        assertFalse(item.ok());
        assertFalse(Files.exists(updateDir.resolve("Foo.jar")));
        assertTrue(tx.get("Foo").isEmpty());
    }

    @Test
    void planAllListsEveryBackup() throws Exception {
        var res = rollback.plan(List.of(info()), List.of());
        assertEquals(1, res.plans().size());
        assertEquals("Foo", res.plans().get(0).backup().plugin());
    }

    @Test
    void identicalBackupIsNotRestored() throws Exception {
        Files.copy(backupDir.resolve("Foo.jar"), installed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var res = rollback.plan(List.of(info()), List.of("Foo"));
        assertEquals(RollbackService.Problem.IDENTICAL, res.failures().get(0).problem());
    }
}
