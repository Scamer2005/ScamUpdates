package de.scamupdates.update;

import com.sun.net.httpserver.HttpServer;
import de.scamupdates.TestJars;
import de.scamupdates.backup.BackupManager;
import de.scamupdates.config.ConfigManager;
import de.scamupdates.history.HistoryEntry;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.match.MatchService;
import de.scamupdates.match.MatchStore;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.net.ConnectivityGuard;
import de.scamupdates.net.HttpService;
import de.scamupdates.net.InternetCheck;
import de.scamupdates.ops.OperationRegistry;
import de.scamupdates.security.OperationLock;
import de.scamupdates.sources.SourceRegistry;
import de.scamupdates.state.PluginState;
import de.scamupdates.state.StateManager;
import de.scamupdates.utils.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SinglePluginUpdateTest {

    @TempDir Path dir;
    Path pluginsDir, updateDir, backupDir, tmpDir, installedJar, v2Jar;
    byte[] installedBytes, v2Bytes;
    String v2Sha;
    HttpServer server;
    ExecutorService io;
    final AtomicReference<byte[]> responseBody = new AtomicReference<>();
    final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    final AtomicReference<InternetCheck.Report> probe = new AtomicReference<>(
            new InternetCheck.Report(InternetCheck.Verdict.CONNECTED, List.of()));

    ConfigManager config;
    StateManager state;
    OperationLock lock;
    BackupManager backups;
    TransactionStore transactions;
    HistoryStore history;
    UpdateService updates;

    @BeforeEach
    void setUp() throws Exception {
        io = Executors.newFixedThreadPool(2);
        pluginsDir = dir.resolve("plugins");
        updateDir = pluginsDir.resolve("update");
        backupDir = pluginsDir.resolve("update-backup");
        tmpDir = pluginsDir.resolve("ScamUpdates/tmp");

        installedJar = TestJars.plugin(pluginsDir.resolve("Alpha.jar"), "Alpha", "1.0", "old-payload");
        installedBytes = Files.readAllBytes(installedJar);
        v2Jar = TestJars.plugin(dir.resolve("remote/Alpha-2.0.jar"), "Alpha", "2.0", "new-payload");
        v2Bytes = Files.readAllBytes(v2Jar);
        v2Sha = Hashing.sha256(v2Jar);
        responseBody.set(v2Bytes);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/Alpha.jar", ex -> {
            int code = responseStatus.get();
            byte[] body = code == 200 ? responseBody.get() : "error".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        ScamLog log = new ScamLog(Logger.getLogger("SingleUpdateTest"), null);
        String defaults = new String(getClass().getResourceAsStream("/config.yml").readAllBytes(), StandardCharsets.UTF_8);
        String userYaml = """
                config-version: 2
                crafty:
                  enabled: false
                downloads:
                  allow-http: true
                http:
                  request-delay-ms: 0
                plugins:
                  Alpha:
                    source: direct
                    download-url: "http://127.0.0.1:%d/Alpha.jar"
                    version: "2.0"
                    sha256: "%s"
                """.formatted(port, v2Sha);
        config = new ConfigManager(null, log);
        assertTrue(config.loadFromString(userYaml, defaults).ok());

        state = new StateManager();
        state.set(PluginState.ACTIVE, List.of("OK"));
        ConnectivityGuard guard = new ConnectivityGuard(state, probe::get, log);

        backups = new BackupManager(backupDir, log);
        transactions = new TransactionStore(pluginsDir.resolve("ScamUpdates/transactions.yml"), log);
        history = new HistoryStore(pluginsDir.resolve("ScamUpdates/history.jsonl"), log, () -> 30, () -> 100);
        lock = new OperationLock();
        HttpService http = new HttpService("Test", Duration.ofSeconds(5), 0, true, log);
        SourceRegistry reg = new SourceRegistry(http, config);
        MatchService matches = new MatchService(config, reg, new MatchStore(pluginsDir.resolve("ScamUpdates/matches.yml"), log), log);
        OperationRegistry ops = new OperationRegistry(pluginsDir.resolve("ScamUpdates/operations.yml"), log);

        updates = new UpdateService(null, config, state, log, http, reg, matches, transactions,
                new Stager(updateDir, backups), lock, io, pluginsDir, tmpDir, ops, null);
        updates.setConnectivity(guard);
        updates.setHistory(history);
        updates.setPluginCollector(() -> List.of(new PluginInfo("Alpha", "1.0", List.of(), null, null, null, installedJar)));
        updates.setMcVersion(() -> "1.21.11");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (io != null) io.shutdownNow();
    }

    @Test
    void checkSingleOnlineUpdatesSnapshotAndOfflineBlocksCleanly() {
        probe.set(new InternetCheck.Report(InternetCheck.Verdict.NO_INTERNET, List.of()));
        state.set(PluginState.ACTIVE_OFFLINE, List.of("offline"));

        CompletionException err = assertThrows(CompletionException.class, () -> updates.checkSingle("Alpha", "Admin").join());
        assertInstanceOf(StateManager.OfflineException.class, err.getCause());

        probe.set(new InternetCheck.Report(InternetCheck.Verdict.CONNECTED, List.of()));
        UpdateService.PluginStatus st = updates.checkSingle("Alpha", "Admin").join();
        assertEquals(UpdateService.Kind.UPDATE_AVAILABLE, st.kind());
        assertEquals("2.0", st.release().version());
        assertEquals(UpdateService.Kind.UPDATE_AVAILABLE, updates.statusFor("Alpha").orElseThrow().kind());
        assertTrue(updates.candidateFor("Alpha").isPresent());
    }

    @Test
    void singleDownloadStagesValidatedJarCreatesBackupAndLeavesInstalledJarUntouched() throws Exception {
        UpdateService.PluginStatus checked = updates.checkSingle("Alpha", "Admin").join();
        assertEquals(UpdateService.Kind.UPDATE_AVAILABLE, checked.kind());

        UpdateService.DownloadReport report = updates.downloadSingle("Alpha", i -> {}, "Admin").join();
        assertEquals(1, report.okCount());
        assertEquals(0, report.failedCount());

        // Installierte JAR darf niemals direkt ueberschrieben werden
        assertArrayEquals(installedBytes, Files.readAllBytes(installedJar));

        // Neue JAR liegt in plugins/update/, Backup von v1.0 in plugins/update-backup/
        Path staged = updateDir.resolve("Alpha.jar");
        assertTrue(Files.isRegularFile(staged));
        assertEquals(v2Sha, Hashing.sha256(staged));
        assertEquals("1.0", backups.get("Alpha").orElseThrow().version());
        assertTrue(backups.verify(backups.get("Alpha").orElseThrow()));
        assertTrue(transactions.get("Alpha").isPresent());
        assertEquals(UpdateService.Kind.PENDING_RESTART, updates.statusFor("Alpha").orElseThrow().kind());

        List<HistoryEntry> entries = history.list(HistoryStore.Sort.NEWEST);
        assertTrue(entries.stream().anyMatch(e -> "plugin-update".equals(e.action()) && "OK".equals(e.result())));
    }

    @Test
    void invalidDownloadsAndErrorsLeaveInstalledJarAndExistingBackupUntouched() throws Exception {
        Path existingOld = TestJars.plugin(dir.resolve("old/Alpha-0.9.jar"), "Alpha", "0.9", "prev-backup");
        backups.commit(backups.prepare("Alpha", "0.9", existingOld));
        String existingBackupSha = backups.get("Alpha").orElseThrow().sha256();

        updates.checkSingle("Alpha", "Admin").join();

        // 1. Falsches Plugin im JAR
        Path wrongJar = TestJars.plugin(dir.resolve("wrong.jar"), "WrongPlugin", "2.0", "bad");
        responseBody.set(Files.readAllBytes(wrongJar));
        UpdateService.DownloadReport r1 = updates.downloadSingle("Alpha", i -> {}, "Admin").join();
        assertEquals(0, r1.okCount());
        assertEquals(1, r1.failedCount());

        // 2. HTTP 500 Serverfehler
        responseStatus.set(500);
        UpdateService.DownloadReport r2 = updates.downloadSingle("Alpha", i -> {}, "Admin").join();
        assertEquals(0, r2.okCount());
        assertEquals(1, r2.failedCount());

        // Installierte JAR, Update-Ordner, Transaktionen und bestehendes Backup bleiben komplett unberuehrt
        assertArrayEquals(installedBytes, Files.readAllBytes(installedJar));
        assertFalse(Files.exists(updateDir.resolve("Alpha.jar")));
        assertTrue(transactions.get("Alpha").isEmpty());
        assertEquals("0.9", backups.get("Alpha").orElseThrow().version());
        assertEquals(existingBackupSha, backups.get("Alpha").orElseThrow().sha256());
        assertTrue(backups.verify(backups.get("Alpha").orElseThrow()));
    }

    @Test
    void concurrentSingleDownloadIsBlockedByOperationLock() {
        updates.checkSingle("Alpha", "Admin").join();
        try (var ignored = lock.tryAcquire("download").orElseThrow()) {
            CompletionException ex = assertThrows(CompletionException.class,
                    () -> updates.downloadSingle("Alpha", i -> {}, "Admin").join());
            assertInstanceOf(UpdateService.BusyException.class, ex.getCause());
        }
    }
}
