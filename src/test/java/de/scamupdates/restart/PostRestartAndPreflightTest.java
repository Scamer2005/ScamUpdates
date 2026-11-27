package de.scamupdates.restart;

import de.scamupdates.TestJars;
import de.scamupdates.backup.BackupManager;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.update.PostRestartVerifier;
import de.scamupdates.update.Stager;
import de.scamupdates.update.TransactionStore;
import de.scamupdates.utils.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PostRestartAndPreflightTest {
    @TempDir Path dir;
    Path plugins, updateDir, installed;
    BackupManager backups;
    TransactionStore tx;
    Stager stager;
    ScamLog log = new ScamLog(Logger.getLogger("test"), null);

    @BeforeEach
    void setUp() throws Exception {
        plugins = dir.resolve("plugins");
        updateDir = plugins.resolve("update");
        installed = TestJars.plugin(plugins.resolve("Foo.jar"), "Foo", "1.0", "v1");
        backups = new BackupManager(plugins.resolve("update-backup"), log);
        tx = new TransactionStore(plugins.resolve("ScamUpdates/transactions.yml"), log);
        stager = new Stager(updateDir, backups);
    }

    private void stage() throws Exception {
        Path fresh = TestJars.plugin(dir.resolve("fresh.jar"), "Foo", "2.0", "v2");
        Stager.Result r = stager.stage("Foo", "1.0", installed, fresh);
        tx.put(new TransactionStore.Pending("Foo", TransactionStore.Kind.UPDATE, "1.0", "2.0", "Foo.jar", r.sha256(), System.currentTimeMillis()));
    }

    private void paperApplies() throws Exception {
        Files.move(updateDir.resolve("Foo.jar"), installed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void appliedUpdateIsDetectedByHashAndRecordCleared() throws Exception {
        stage();
        paperApplies();
        List<PostRestartVerifier.Report> r = PostRestartVerifier.verify(tx, plugins, updateDir, log);
        assertEquals(PostRestartVerifier.Status.APPLIED, r.get(0).status());
        assertTrue(tx.all().isEmpty());
    }

    @Test
    void updateStillInFolderIsReportedAsNotApplied() throws Exception {
        stage();
        List<PostRestartVerifier.Report> r = PostRestartVerifier.verify(tx, plugins, updateDir, log);
        assertEquals(PostRestartVerifier.Status.NOT_APPLIED, r.get(0).status());
        assertEquals(1, tx.all().size(), "Eintrag bleibt bestehen");
    }

    @Test
    void foreignChangeIsReportedAsMismatch() throws Exception {
        stage();
        Files.delete(updateDir.resolve("Foo.jar"));
        List<PostRestartVerifier.Report> r = PostRestartVerifier.verify(tx, plugins, updateDir, log);
        assertEquals(PostRestartVerifier.Status.MISMATCH, r.get(0).status());
        assertTrue(tx.all().isEmpty());
    }

    @Test
    void preflightPassesForIntactStagedUpdate() throws Exception {
        stage();
        RestartService svc = new RestartService(null, null, log, null, null, tx, backups, updateDir, () -> null, () -> "1.21.11");
        assertTrue(svc.preflight(tx.all()).isEmpty());
    }

    @Test
    void preflightFailsForMissingModifiedOrBackuplessFiles() throws Exception {
        stage();
        RestartService svc = new RestartService(null, null, log, null, null, tx, backups, updateDir, () -> null, () -> "1.21.11");

        Files.writeString(updateDir.resolve("Foo.jar"), "veraendert");
        assertFalse(svc.preflight(tx.all()).isEmpty());

        Files.delete(updateDir.resolve("Foo.jar"));
        assertFalse(svc.preflight(tx.all()).isEmpty());

        // intakte Datei, aber Backup geloescht
        Path fresh = TestJars.plugin(updateDir.resolve("Foo.jar"), "Foo", "2.0", "v2");
        tx.put(new TransactionStore.Pending("Foo", TransactionStore.Kind.UPDATE, "1.0", "2.0", "Foo.jar", Hashing.sha256(fresh), 0));
        assertTrue(svc.preflight(tx.all()).isEmpty());
        backups.deleteAll();
        List<String> problems = svc.preflight(tx.all());
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("Backup"));
    }
}
