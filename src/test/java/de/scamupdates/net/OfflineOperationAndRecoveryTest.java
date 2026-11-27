package de.scamupdates.net;

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
import de.scamupdates.ops.OperationRegistry;
import de.scamupdates.queue.UpdateQueueStore;
import de.scamupdates.rollback.RollbackService;
import de.scamupdates.security.ConfirmationManager;
import de.scamupdates.security.OperationLock;
import de.scamupdates.security.Perm;
import de.scamupdates.sources.SourceRegistry;
import de.scamupdates.state.PluginState;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.Stager;
import de.scamupdates.update.TransactionStore;
import de.scamupdates.update.UpdateService;
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
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OfflineOperationAndRecoveryTest {

    @TempDir Path dir;
    Path pluginsDir, updateDir, backupDir, tmpDir, installedJar, v2SourceJar;
    HttpServer server;
    int port;
    final AtomicInteger sourceHits = new AtomicInteger();
    final AtomicReference<InternetCheck.Report> probeResult = new AtomicReference<>(
            offlineReport());
    final ScamLog log = new ScamLog(Logger.getLogger("OfflineTest"), null);
    ExecutorService io;

    StateManager state;
    ConnectivityGuard connectivity;
    ConfigManager config;
    BackupManager backups;
    TransactionStore transactions;
    HistoryStore history;
    MatchStore matchStore;
    UpdateQueueStore queue;
    UpdateService updates;
    RollbackService rollback;

    @BeforeEach
    void setUp() throws Exception {
        io = Executors.newFixedThreadPool(2);
        pluginsDir = dir.resolve("plugins");
        updateDir = pluginsDir.resolve("update");
        backupDir = pluginsDir.resolve("update-backup");
        tmpDir = pluginsDir.resolve("ScamUpdates/tmp");
        installedJar = TestJars.plugin(pluginsDir.resolve("Foo.jar"), "Foo", "1.0", "old");
        v2SourceJar = TestJars.plugin(dir.resolve("remote/Foo-2.0.jar"), "Foo", "2.0", "new");
        byte[] v2Bytes = Files.readAllBytes(v2SourceJar);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/Foo.jar", ex -> {
            sourceHits.incrementAndGet();
            ex.sendResponseHeaders(200, v2Bytes.length);
            ex.getResponseBody().write(v2Bytes);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();

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
                  Foo:
                    source: direct
                    download-url: "http://127.0.0.1:%d/Foo.jar"
                    version: "2.0"
                    sha256: "%s"
                """.formatted(port, Hashing.sha256(v2SourceJar));

        config = new ConfigManager(null, log);
        assertTrue(config.loadFromString(userYaml, defaults).ok());

        state = new StateManager();
        state.set(PluginState.ACTIVE_OFFLINE, List.of("Initialisierung"));
        connectivity = new ConnectivityGuard(state, probeResult::get, log);

        backups = new BackupManager(backupDir, log);
        transactions = new TransactionStore(pluginsDir.resolve("ScamUpdates/transactions.yml"), log);
        Stager stager = new Stager(updateDir, backups);
        history = new HistoryStore(pluginsDir.resolve("ScamUpdates/history.jsonl"), log, () -> 30, () -> 100);
        matchStore = new MatchStore(pluginsDir.resolve("ScamUpdates/matches.yml"), log);
        queue = new UpdateQueueStore(pluginsDir.resolve("ScamUpdates/queue.yml"), log);
        OperationLock lock = new OperationLock();
        HttpService http = new HttpService("ScamUpdates-Test", Duration.ofSeconds(5), 0, true, log);
        SourceRegistry registry = new SourceRegistry(http, config);
        MatchService matches = new MatchService(config, registry, matchStore, log);
        OperationRegistry ops = new OperationRegistry(pluginsDir.resolve("ScamUpdates/operations.yml"), log);

        updates = new UpdateService(null, config, state, log, http, registry, matches, transactions, stager,
                lock, io, pluginsDir, tmpDir, ops, null);
        updates.setConnectivity(connectivity);
        updates.setQueue(queue);
        updates.setHistory(history);
        updates.setPluginCollector(() -> List.of(new PluginInfo("Foo", "1.0", List.of(), null, null, null, installedJar)));
        updates.setMcVersion(() -> "1.21.11");

        rollback = new RollbackService(state, backups, stager, transactions, log, lock, io, pluginsDir, tmpDir, () -> "1.21.11");
        rollback.setHistory(history);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (io != null) io.shutdownNow();
    }

    @Test
    void offlineProbeSetsActiveOfflineWithoutTouchingDisabledStates() {
        assertFalse(connectivity.refresh());
        assertEquals(PluginState.ACTIVE_OFFLINE, state.state());
        assertTrue(state.isOperational());
        assertTrue(state.isActive());
        assertFalse(state.isOnline());
        assertDoesNotThrow(state::requireActive);
        assertThrows(StateManager.OfflineException.class, state::requireOnline);

        StateManager disabled = new StateManager();
        disabled.set(PluginState.DISABLED_CONFIGURATION_ERROR, List.of("kaputt"));
        ConnectivityGuard disabledGuard = new ConnectivityGuard(disabled,
                OfflineOperationAndRecoveryTest::onlineReport, log);
        assertFalse(disabledGuard.refresh());
        assertEquals(PluginState.DISABLED_CONFIGURATION_ERROR, disabled.state(),
                "DISABLED_* darf durch Konnektivitaetspruefung nie ueberschrieben werden");
    }

    @Test
    void onlineActionsAreBlockedOfflineWhileLocalBackupsRestoreHistoryAndConfirmationsKeepWorking() throws Exception {
        assertFalse(connectivity.refresh());
        assertEquals(PluginState.ACTIVE_OFFLINE, state.state());

        // Online check wird sauber mit OfflineException blockiert (keine HTTP-Anfrage)
        CompletionException checkErr = assertThrows(CompletionException.class, () -> updates.check("admin").join());
        assertInstanceOf(StateManager.OfflineException.class, checkErr.getCause());
        assertEquals(0, sourceHits.get());

        // Online download wird ebenfalls sauber mit OfflineException blockiert (auch bei konkreten Kandidaten)
        var candidate = new de.scamupdates.model.UpdateCandidate(
                new PluginInfo("Foo", "1.0", List.of(), null, null, null, installedJar),
                new de.scamupdates.model.ProjectRef("direct", "Foo", "Foo", "http://127.0.0.1:" + port + "/Foo.jar"),
                new de.scamupdates.model.RemoteRelease("2.0", de.scamupdates.model.Channel.STABLE,
                        "http://127.0.0.1:" + port + "/Foo.jar", "Foo.jar", java.util.Map.of(), -1, List.of(), false));
        CompletionException dlErr = assertThrows(CompletionException.class, () -> updates.download(List.of(candidate), i -> {}, "admin").join());
        assertInstanceOf(StateManager.OfflineException.class, dlErr.getCause());
        assertEquals(0, sourceHits.get());

        // Lokale Funktionen (Backup, Restore, History, MatchStore, Confirmations) bleiben komplett nutzbar
        Path oldV05 = TestJars.plugin(dir.resolve("old/Foo-0.5.jar"), "Foo", "0.5", "older");
        backups.commit(backups.prepare("Foo", "0.5", oldV05));
        assertEquals(1, backups.list().size());

        List<PluginInfo> installedList = List.of(new PluginInfo("Foo", "1.0", List.of(), null, null, null, installedJar));
        RollbackService.PlanResult plan = rollback.plan(installedList, List.of("Foo"));
        assertTrue(plan.failures().isEmpty());
        assertEquals(1, plan.plans().size());

        List<RollbackService.Item> restored = rollback.executeAll(plan.plans(), it -> {}, "admin").join();
        assertEquals(1, restored.size());
        assertTrue(restored.get(0).ok());
        assertTrue(Files.isRegularFile(updateDir.resolve("Foo.jar")));
        assertEquals("1.0", backups.get("Foo").orElseThrow().version());

        history.append(HistoryEntry.of("offline-test", "INFO", "admin", "Lokal ausgefuehrt"));
        assertFalse(history.list(HistoryStore.Sort.NEWEST).isEmpty());

        AtomicBoolean confirmed = new AtomicBoolean(false);
        ConfirmationManager cm = new ConfirmationManager(System::currentTimeMillis, () -> 30, () -> 5, () -> 6);
        UUID player = UUID.randomUUID();
        ConfirmationManager.Pending pending = cm.create(player, "restore", Perm.RESTORE, () -> confirmed.set(true));
        ConfirmationManager.Outcome outcome = cm.confirm(pending.token(), player, p -> true);
        assertEquals(ConfirmationManager.ConfirmResult.OK, outcome.result());
        outcome.pending().run().run();
        assertTrue(confirmed.get());

        // Backup-Loeschung funktioniert lokal ebenfalls im Offline-Modus
        Path barOld = TestJars.plugin(dir.resolve("old/Bar-1.0.jar"), "Bar", "1.0", "old");
        backups.commit(backups.prepare("Bar", "1.0", barOld));
        assertTrue(backups.delete("Bar").isPresent());

        matchStore.exclude("Bar", MatchStore.Reason.MANUAL_COMMAND);
        assertTrue(matchStore.exclusion("Bar").isPresent());
        matchStore.removeExclusion("Bar");
        assertTrue(matchStore.exclusion("Bar").isEmpty());
    }

    @Test
    void recoveryToOnlineReenablesCheckAndSinglePluginDownload() throws Exception {
        assertFalse(connectivity.refresh());
        assertEquals(PluginState.ACTIVE_OFFLINE, state.state());

        // Internet wird wiederhergestellt
        probeResult.set(onlineReport());

        UpdateService.Snapshot snap = updates.check("admin").join();
        assertEquals(PluginState.ACTIVE, state.state());
        assertTrue(state.isOnline());
        assertEquals(1, snap.updates().size());
        assertEquals("2.0", snap.updates().get(0).toVersion());
        assertTrue(updates.candidateFor("Foo").isPresent());
        assertEquals(UpdateService.Kind.UPDATE_AVAILABLE, updates.statusFor("Foo").orElseThrow().kind());

        // Warteschlange einreihen und per Einzelplugin-Download automatisch bereinigen lassen
        queue.enqueue(snap.updates().get(0), "admin");
        assertTrue(queue.contains("Foo"));

        UpdateService.DownloadReport report = updates.downloadSingle("Foo", i -> {}, "admin").join();
        assertEquals(1, report.okCount());
        assertEquals(0, report.failedCount());
        assertTrue(Files.isRegularFile(updateDir.resolve("Foo.jar")));
        assertEquals(Hashing.sha256(v2SourceJar), Hashing.sha256(updateDir.resolve("Foo.jar")));
        assertFalse(queue.contains("Foo"), "Nach erfolgreichem Einzelupdate wird der Warteschlangeneintrag entfernt");

        // Unbekanntes/nicht aktualisierbares Plugin liefert leeren Bericht ohne Fehler
        UpdateService.DownloadReport empty = updates.downloadSingle("DoesNotExist", i -> {}, "admin").join();
        assertTrue(empty.items().isEmpty());
        assertEquals(0, empty.okCount());
    }

    private static InternetCheck.Report offlineReport() {
        return new InternetCheck.Report(InternetCheck.Verdict.NO_INTERNET,
                List.of(new InternetCheck.ProbeResult("https://test.invalid", InternetCheck.Outcome.UNREACHABLE, "offline")));
    }

    private static InternetCheck.Report onlineReport() {
        return new InternetCheck.Report(InternetCheck.Verdict.CONNECTED,
                List.of(new InternetCheck.ProbeResult("https://test.invalid", InternetCheck.Outcome.OK, "HTTP 200")));
    }
}
