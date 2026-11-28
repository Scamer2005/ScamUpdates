package de.scamupdates.queue;

import com.sun.net.httpserver.HttpServer;
import de.scamupdates.TestJars;
import de.scamupdates.backup.BackupManager;
import de.scamupdates.config.ConfigManager;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.match.MatchService;
import de.scamupdates.match.MatchStore;
import de.scamupdates.model.Channel;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.model.RemoteRelease;
import de.scamupdates.model.UpdateCandidate;
import de.scamupdates.net.ConnectivityGuard;
import de.scamupdates.net.HttpService;
import de.scamupdates.net.InternetCheck;
import de.scamupdates.ops.OperationRegistry;
import de.scamupdates.security.OperationLock;
import de.scamupdates.sources.SourceRegistry;
import de.scamupdates.state.PluginState;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.PostRestartVerifier;
import de.scamupdates.update.Stager;
import de.scamupdates.update.TransactionStore;
import de.scamupdates.update.UpdateService;
import de.scamupdates.utils.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class QueueServiceTest {

    @TempDir Path dir;

    private ConfigManager loadConfig(String extraYaml) throws Exception {
        String defaults = new String(getClass().getResourceAsStream("/config.yml").readAllBytes(), StandardCharsets.UTF_8);
        ScamLog log = new ScamLog(Logger.getLogger("QueueServiceTest"), null);
        ConfigManager cfg = new ConfigManager(null, log);
        assertTrue(cfg.loadFromString(extraYaml, defaults).ok());
        return cfg;
    }

    @Test
    void persistentQueueSurvivesReloadPreventsDuplicatesAndNormalizesCorruptYaml() throws Exception {
        ScamLog log = new ScamLog(Logger.getLogger("QueuePersistTest"), null);
        Path file = dir.resolve("queue.yml");
        Files.writeString(file, """
                queue:
                  badscalar: "broken"
                  emptyname:
                    plugin: "   "
                  vault1:
                    plugin: Vault
                    source: modrinth
                  vault2:
                    plugin: vault
                    source: hangar
                  nonameid:
                    plugin: LuckPerms
                    source: modrinth
                """);

        UpdateQueueStore store = new UpdateQueueStore(file, log);
        List<UpdateQueueStore.Entry> entries = store.all();
        assertEquals(2, entries.size(), "Duplikate und defekte Eintraege muessen sauber bereinigt werden");
        UpdateQueueStore.Entry vault = store.get("VAULT").orElseThrow();
        assertFalse(vault.id().isBlank());
        assertEquals(vault.id(), store.getById(vault.id()).orElseThrow().id());

        assertFalse(store.enqueueIfAbsent("vault", "1.7.0", "modrinth", "QUEUED", "AdminA"));
        assertTrue(store.enqueueIfAbsent("EssentialsX", "2.20.0", "modrinth", "QUEUED", "AdminA"));
        String oldVaultId = vault.id();
        store.enqueue("Vault", "1.7.0", "1.8.0", "hangar", "UPDATE_AVAILABLE", "AdminB");
        assertEquals(oldVaultId, store.get("Vault").orElseThrow().id(), "Bestehende UUID bleibt erhalten");

        UpdateQueueStore reloaded = new UpdateQueueStore(file, log);
        assertEquals(3, reloaded.all().size());
        assertEquals("1.8.0", reloaded.get("Vault").orElseThrow().targetVersion());
    }

    @Test
    void waitingTransitionsToReadyAndAmbiguousNeverAutoSelectsAndChooseNoneStartsNoUpdate() throws Exception {
        ScamLog log = new ScamLog(Logger.getLogger("QueueEvalTest"), null);
        ConfigManager cfg = loadConfig("config-version: 2\ncrafty:\n  enabled: false\nmatching:\n  auto-assign-single-match: false\n");
        UpdateQueueStore store = new UpdateQueueStore(dir.resolve("queue.yml"), log);
        QueueService service = new QueueService(store, cfg, log);
        HistoryStore history = new HistoryStore(dir.resolve("history.jsonl"), log, () -> 30, () -> 100);
        service.setHistory(history);

        MatchStore matchStore = new MatchStore(dir.resolve("matches.yml"), log);
        HttpService http = new HttpService("ScamUpdates-Test", Duration.ofSeconds(3), 0, false, log);
        SourceRegistry reg = new SourceRegistry(http, cfg);
        MatchService matches = new MatchService(cfg, reg, matchStore, log);

        PluginInfo alpha = new PluginInfo("Alpha", "1.0", List.of(), null,
                "https://modrinth.com/plugin/alpha https://hangar.papermc.io/org/alpha", null, dir.resolve("Alpha.jar"));
        PluginInfo beta = new PluginInfo("Beta", "1.0", List.of(), null, null, null, dir.resolve("Beta.jar"));
        ProjectRef betaRef = new ProjectRef("modrinth", "beta", "Beta", "https://modrinth.com/project/beta");

        // Vorab vorgemerkt ohne vorliegendes Update
        store.enqueueIfAbsent("Alpha", "1.0", "", "QUEUED", "Admin");
        store.enqueueIfAbsent("Beta", "1.0", "modrinth", "QUEUED", "Admin");

        // 1. Snapshot: Beta aktuell -> WAITING; Alpha noch ungeprueft -> WAITING
        UpdateService.Snapshot upToDateSnap = new UpdateService.Snapshot(1000L, List.of(
                new UpdateService.PluginStatus(beta, UpdateService.Kind.UP_TO_DATE, betaRef, null, null)
        ));
        List<QueueService.EvaluatedEntry> firstEval = service.evaluate(upToDateSnap, matches, Set.of("Alpha", "Beta"), 2000L);
        assertEquals(QueueService.EntryState.WAITING, firstEval.get(0).state());
        assertEquals(QueueService.EntryState.WAITING, firstEval.get(1).state());
        assertTrue(service.pendingNotifications(upToDateSnap, matches, Set.of("Alpha", "Beta"), 2000L).isEmpty());

        // 2. Mehrdeutige Treffer fuer Alpha vorbereiten (2 Kandidaten) -> AMBIGUOUS (niemals automatisch gewaehlt!)
        MatchService.Resolution res = matches.resolve(alpha);
        assertEquals(MatchService.Type.PENDING, res.type());
        assertEquals(2, res.candidates().size());

        RemoteRelease betaV2 = new RemoteRelease("2.0", Channel.STABLE, "https://example.org/beta-2.0.jar",
                "Beta-2.0.jar", Map.of(), -1, List.of(), false);
        UpdateService.Snapshot readySnap = new UpdateService.Snapshot(3000L, List.of(
                new UpdateService.PluginStatus(alpha, UpdateService.Kind.PENDING_MATCH, null, null, null),
                new UpdateService.PluginStatus(beta, UpdateService.Kind.UPDATE_AVAILABLE, betaRef, betaV2, null)
        ));

        List<QueueService.EvaluatedEntry> evaluated = service.evaluate(readySnap, matches, Set.of("Alpha", "Beta"), 3500L);
        assertEquals(QueueService.EntryState.AMBIGUOUS, evaluated.get(0).state());
        assertEquals(2, evaluated.get(0).candidates().size());
        assertEquals(QueueService.EntryState.READY, evaluated.get(1).state());
        assertEquals("2.0", evaluated.get(1).candidate().toVersion());

        List<QueueService.EvaluatedEntry> notify = service.pendingNotifications(readySnap, matches, Set.of("Alpha", "Beta"), 3500L);
        assertEquals(2, notify.size());

        // "Keins davon" fuer Alpha waehlen -> dauerhaft ausgeschlossen, kein Update gestartet
        assertEquals(MatchService.PickResult.EXCLUDED, service.chooseNone("Alpha", matches, "Admin"));
        assertEquals(MatchStore.Reason.NONE_OF_THESE, matchStore.exclusion("Alpha").orElseThrow().reason());
        assertTrue(matches.pendingFor("Alpha").isEmpty());

        UpdateService.Snapshot afterNone = new UpdateService.Snapshot(4000L, List.of(
                new UpdateService.PluginStatus(alpha, UpdateService.Kind.EXCLUDED, null, null, null),
                new UpdateService.PluginStatus(beta, UpdateService.Kind.UPDATE_AVAILABLE, betaRef, betaV2, null)
        ));
        assertEquals(QueueService.EntryState.EXCLUDED,
                service.evaluate(afterNone, matches, Set.of("Alpha", "Beta"), 4500L).get(0).state());
    }

    @Test
    void snoozeLaterPreservesEntryAndReNotifiesAfterExpiryWhileDismissRemovesPermanently() throws Exception {
        ScamLog log = new ScamLog(Logger.getLogger("QueueSnoozeTest"), null);
        ConfigManager cfg = loadConfig("""
                config-version: 2
                crafty:
                  enabled: false
                queue:
                  snooze-minutes: 30
                """);
        Path file = dir.resolve("queue.yml");
        UpdateQueueStore store = new UpdateQueueStore(file, log);
        QueueService service = new QueueService(store, cfg, log);

        PluginInfo gamma = new PluginInfo("Gamma", "1.0", List.of(), null, null, null, dir.resolve("Gamma.jar"));
        ProjectRef ref = new ProjectRef("modrinth", "gamma", "Gamma", "https://modrinth.com/project/gamma");
        RemoteRelease rel = new RemoteRelease("1.5", Channel.STABLE, "https://example.org/gamma.jar",
                "Gamma-1.5.jar", Map.of(), -1, List.of(), false);
        UpdateService.Snapshot snap = new UpdateService.Snapshot(10_000L, List.of(
                new UpdateService.PluginStatus(gamma, UpdateService.Kind.UPDATE_AVAILABLE, ref, rel, null)));

        store.enqueueIfAbsent("Gamma", "1.0", "modrinth", "QUEUED", "Admin");
        long t0 = 100_000L;
        List<QueueService.EvaluatedEntry> due1 = service.pendingNotifications(snap, null, Set.of("Gamma"), t0);
        assertEquals(1, due1.size());
        assertTrue(service.markNotified(due1.get(0)));
        assertTrue(service.pendingNotifications(snap, null, Set.of("Gamma"), t0 + 1000L).isEmpty(),
                "Bereits gemeldete Version darf nicht sofort erneut nerven");

        // Neue Version 1.6 wird verfuegbar -> admin waehlt "Spaeter"
        RemoteRelease rel16 = new RemoteRelease("1.6", Channel.STABLE, "https://example.org/gamma-1.6.jar",
                "Gamma-1.6.jar", Map.of(), -1, List.of(), false);
        UpdateService.Snapshot snap16 = new UpdateService.Snapshot(200_000L, List.of(
                new UpdateService.PluginStatus(gamma, UpdateService.Kind.UPDATE_AVAILABLE, ref, rel16, null)));

        assertTrue(service.snooze("Gamma", "Admin", t0));
        assertTrue(store.contains("Gamma"), "Spaeter muss Eintrag dauerhaft behalten");
        assertEquals(QueueService.EntryState.SNOOZED,
                service.evaluate(snap16, null, Set.of("Gamma"), t0 + 5 * 60_000L).get(0).state());
        assertTrue(service.pendingNotifications(snap16, null, Set.of("Gamma"), t0 + 5 * 60_000L).isEmpty());

        // Nach Ablauf der 30 Minuten muss wieder READY + Benachrichtigung erscheinen
        long afterSnooze = t0 + 31 * 60_000L;
        assertEquals(QueueService.EntryState.READY,
                service.evaluate(snap16, null, Set.of("Gamma"), afterSnooze).get(0).state());
        assertEquals(1, service.pendingNotifications(snap16, null, Set.of("Gamma"), afterSnooze).size());

        // "Nicht mehr erinnern" loescht Eintrag dauerhaft (auch nach Reload)
        assertTrue(service.dismiss("Gamma", "Admin"));
        assertFalse(store.contains("Gamma"));
        assertTrue(new UpdateQueueStore(file, log).all().isEmpty());
    }

    @Test
    void failedDownloadAndOfflineKeepQueueWhileSuccessfulDownloadPrunesIt() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger statusToServe = new AtomicInteger(500);
        Path pluginsDir = dir.resolve("plugins");
        Path installed = TestJars.plugin(pluginsDir.resolve("Delta.jar"), "Delta", "1.0", "old");
        Path v2Jar = TestJars.plugin(dir.resolve("remote/Delta-2.0.jar"), "Delta", "2.0", "new");
        byte[] v2Bytes = Files.readAllBytes(v2Jar);
        String v2Sha = Hashing.sha256(v2Jar);

        server.createContext("/Delta.jar", ex -> {
            int code = statusToServe.get();
            if (code != 200) {
                byte[] err = "err".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(code, err.length);
                ex.getResponseBody().write(err);
            } else {
                ex.sendResponseHeaders(200, v2Bytes.length);
                ex.getResponseBody().write(v2Bytes);
            }
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ScamLog log = new ScamLog(Logger.getLogger("QueueDownloadTest"), null);
            ConfigManager cfg = loadConfig("""
                    config-version: 2
                    crafty:
                      enabled: false
                    downloads:
                      allow-http: true
                    http:
                      request-delay-ms: 0
                    plugins:
                      Delta:
                        source: direct
                        download-url: "http://127.0.0.1:%d/Delta.jar"
                        version: "2.0"
                        sha256: "%s"
                    """.formatted(port, v2Sha));

            StateManager state = new StateManager();
            state.set(PluginState.ACTIVE_OFFLINE, List.of("offline"));
            AtomicReference<InternetCheck.Report> probe = new AtomicReference<>(
                    new InternetCheck.Report(InternetCheck.Verdict.NO_INTERNET, List.of()));
            ConnectivityGuard guard = new ConnectivityGuard(state, probe::get, log);

            BackupManager backups = new BackupManager(pluginsDir.resolve("update-backup"), log);
            TransactionStore tx = new TransactionStore(pluginsDir.resolve("ScamUpdates/transactions.yml"), log);
            UpdateQueueStore queue = new UpdateQueueStore(pluginsDir.resolve("ScamUpdates/queue.yml"), log);
            MatchStore ms = new MatchStore(pluginsDir.resolve("ScamUpdates/matches.yml"), log);
            HttpService http = new HttpService("Test", Duration.ofSeconds(5), 0, true, log);
            MatchService matches = new MatchService(cfg, new SourceRegistry(http, cfg), ms, log);
            OperationRegistry ops = new OperationRegistry(pluginsDir.resolve("ScamUpdates/operations.yml"), log);

            UpdateService us = new UpdateService(null, cfg, state, log, http, new SourceRegistry(http, cfg), matches, tx,
                    new Stager(pluginsDir.resolve("update"), backups), new OperationLock(), io,
                    pluginsDir, pluginsDir.resolve("ScamUpdates/tmp"), ops, null);
            us.setConnectivity(guard);
            us.setQueue(queue);
            us.setPluginCollector(() -> List.of(new PluginInfo("Delta", "1.0", List.of(), null, null, null, installed)));
            us.setMcVersion(() -> "1.21.11");

            queue.enqueueIfAbsent("Delta", "1.0", "direct", "QUEUED", "Admin");

            // Im Offline-Betrieb bleibt die Warteschlange erhalten
            CompletionException offlineErr = assertThrows(CompletionException.class, () -> us.check("Admin").join());
            assertInstanceOf(StateManager.OfflineException.class, offlineErr.getCause());
            assertTrue(queue.contains("Delta"));

            // Online mit HTTP-500-Downloadfehler -> Queue-Eintrag darf NICHT geloescht werden
            probe.set(new InternetCheck.Report(InternetCheck.Verdict.CONNECTED, List.of()));
            UpdateService.Snapshot snap = us.check("Admin").join();
            assertEquals(1, snap.updates().size());

            UpdateService.DownloadReport failed = us.downloadSingle("Delta", i -> {}, "Admin").join();
            assertEquals(0, failed.okCount());
            assertEquals(1, failed.failedCount());
            assertTrue(queue.contains("Delta"), "Fehlgeschlagener Download darf Queue-Eintrag nicht entfernen");

            // Erfolgreicher Download bereinigt die Warteschlange
            statusToServe.set(200);
            UpdateService.DownloadReport ok = us.downloadSingle("Delta", i -> {}, "Admin").join();
            assertEquals(1, ok.okCount());
            assertFalse(queue.contains("Delta"), "Erfolgreicher Download bereinigt Queue-Eintrag");
        } finally {
            server.stop(0);
            io.shutdownNow();
        }
    }

    @Test
    void multiPluginDownloadPartialFailureAndAllOrNothingAbortPreserveConsistencyInQueueYml() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Path pluginsDir = dir.resolve("plugins-multi");
        Path echoInstalled = TestJars.plugin(pluginsDir.resolve("Echo.jar"), "Echo", "1.0", "old");
        Path foxInstalled = TestJars.plugin(pluginsDir.resolve("Foxtrot.jar"), "Foxtrot", "1.0", "old");
        Path echoV2 = TestJars.plugin(dir.resolve("remote-multi/Echo-2.0.jar"), "Echo", "2.0", "new");
        byte[] echoBytes = Files.readAllBytes(echoV2);
        String echoSha = Hashing.sha256(echoV2);

        server.createContext("/Echo.jar", ex -> {
            ex.sendResponseHeaders(200, echoBytes.length);
            ex.getResponseBody().write(echoBytes);
            ex.close();
        });
        server.createContext("/Foxtrot.jar", ex -> {
            byte[] err = "server-error".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, err.length);
            ex.getResponseBody().write(err);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ScamLog log = new ScamLog(Logger.getLogger("QueueMultiTest"), null);
            String partialCfgYaml = """
                    config-version: 2
                    crafty:
                      enabled: false
                    downloads:
                      allow-http: true
                      all-or-nothing: false
                    http:
                      request-delay-ms: 0
                    plugins:
                      Echo:
                        source: direct
                        download-url: "http://127.0.0.1:%d/Echo.jar"
                        version: "2.0"
                        sha256: "%s"
                      Foxtrot:
                        source: direct
                        download-url: "http://127.0.0.1:%d/Foxtrot.jar"
                        version: "2.0"
                        sha256: "%s"
                    """.formatted(port, echoSha, port, echoSha);
            ConfigManager partialCfg = loadConfig(partialCfgYaml);

            StateManager state = new StateManager();
            state.set(PluginState.ACTIVE, List.of("OK"));
            BackupManager backups = new BackupManager(pluginsDir.resolve("update-backup"), log);
            Path txFile = pluginsDir.resolve("ScamUpdates/transactions.yml");
            Path qFile = pluginsDir.resolve("ScamUpdates/queue.yml");
            TransactionStore tx = new TransactionStore(txFile, log);
            UpdateQueueStore queue = new UpdateQueueStore(qFile, log);
            HttpService http = new HttpService("Test", Duration.ofSeconds(5), 0, true, log);
            SourceRegistry reg = new SourceRegistry(http, partialCfg);
            MatchService matches = new MatchService(partialCfg, reg,
                    new MatchStore(pluginsDir.resolve("ScamUpdates/matches.yml"), log), log);
            OperationRegistry ops = new OperationRegistry(pluginsDir.resolve("ScamUpdates/operations.yml"), log);

            UpdateService us = new UpdateService(null, partialCfg, state, log, http, reg, matches, tx,
                    new Stager(pluginsDir.resolve("update"), backups), new OperationLock(), io,
                    pluginsDir, pluginsDir.resolve("ScamUpdates/tmp"), ops, null);
            us.setQueue(queue);
            us.setPluginCollector(() -> List.of(
                    new PluginInfo("Echo", "1.0", List.of(), null, null, null, echoInstalled),
                    new PluginInfo("Foxtrot", "1.0", List.of(), null, null, null, foxInstalled)
            ));
            us.setMcVersion(() -> "1.21.11");

            queue.enqueueIfAbsent("Echo", "1.0", "direct", "QUEUED", "Admin");
            queue.enqueueIfAbsent("Foxtrot", "1.0", "direct", "QUEUED", "Admin");

            UpdateService.Snapshot snap = us.check("Admin").join();
            UpdateService.DownloadReport rep = us.download(snap.updates(), i -> {}, "Admin").join();
            assertEquals(1, rep.okCount());
            assertEquals(1, rep.failedCount());
            assertFalse(rep.abortedAllOrNothing());

            // Echo wurde gestagt -> sofort aus queue.yml entfernt; Foxtrot fehlgeschlagen -> bleibt in queue.yml
            UpdateQueueStore reloaded = new UpdateQueueStore(qFile, log);
            assertFalse(reloaded.contains("Echo"), "Erfolgreich gestagtes Plugin muss zeitnah aus queue.yml entfernt sein");
            assertTrue(reloaded.contains("Foxtrot"), "Fehlgeschlagenes Plugin muss in queue.yml verbleiben");
            assertTrue(tx.get("Echo").isPresent());
            assertTrue(tx.get("Foxtrot").isEmpty());

            // Test 2: all-or-nothing = true -> nichts gestagt, Queue komplett unberuehrt
            ConfigManager strictCfg = loadConfig(partialCfgYaml.replace("all-or-nothing: false", "all-or-nothing: true"));
            Path qFile2 = pluginsDir.resolve("ScamUpdates/queue-strict.yml");
            Path txFile2 = pluginsDir.resolve("ScamUpdates/tx-strict.yml");
            UpdateQueueStore strictQueue = new UpdateQueueStore(qFile2, log);
            strictQueue.enqueueIfAbsent("Echo", "1.0", "direct", "QUEUED", "Admin");
            strictQueue.enqueueIfAbsent("Foxtrot", "1.0", "direct", "QUEUED", "Admin");
            UpdateService strictUs = new UpdateService(null, strictCfg, state, log, http,
                    new SourceRegistry(http, strictCfg), matches, new TransactionStore(txFile2, log),
                    new Stager(pluginsDir.resolve("update-strict"), backups), new OperationLock(), io,
                    pluginsDir, pluginsDir.resolve("ScamUpdates/tmp"), ops, null);
            strictUs.setQueue(strictQueue);
            strictUs.setPluginCollector(() -> List.of(
                    new PluginInfo("Echo", "1.0", List.of(), null, null, null, echoInstalled),
                    new PluginInfo("Foxtrot", "1.0", List.of(), null, null, null, foxInstalled)
            ));
            strictUs.setMcVersion(() -> "1.21.11");

            UpdateService.Snapshot snap2 = strictUs.check("Admin").join();
            UpdateService.DownloadReport aborted = strictUs.download(snap2.updates(), i -> {}, "Admin").join();
            assertTrue(aborted.abortedAllOrNothing());
            assertTrue(strictQueue.contains("Echo"));
            assertTrue(strictQueue.contains("Foxtrot"));
        } finally {
            server.stop(0);
            io.shutdownNow();
        }
    }

    @Test
    void interruptedQueueCleanupAfterStagingIsReconciledByPostRestartVerifierOnStartup() throws Exception {
        ScamLog log = new ScamLog(Logger.getLogger("QueueCrashVerifyTest"), null);
        Path pluginsDir = dir.resolve("plugins-crash");
        Path updateDir = pluginsDir.resolve("update");
        Path installed = TestJars.plugin(pluginsDir.resolve("Echo.jar"), "Echo", "1.0", "old");
        Path stagedSrc = TestJars.plugin(dir.resolve("Echo-2.0.jar"), "Echo", "2.0", "new");
        BackupManager backups = new BackupManager(pluginsDir.resolve("update-backup"), log);
        Stager stager = new Stager(updateDir, backups);
        Stager.Result sr = stager.stage("Echo", "1.0", installed, stagedSrc);

        TransactionStore tx = new TransactionStore(pluginsDir.resolve("ScamUpdates/transactions.yml"), log);
        tx.put(new TransactionStore.Pending("Echo", TransactionStore.Kind.UPDATE, "1.0", "2.0",
                sr.stagedFile().getFileName().toString(), sr.sha256(), System.currentTimeMillis()));

        // Absturz zwischen Transaktionspersistenz und Queue-Bereinigung simulieren: Echo steht noch in queue.yml
        Path qFile = pluginsDir.resolve("ScamUpdates/queue.yml");
        UpdateQueueStore queue = new UpdateQueueStore(qFile, log);
        queue.enqueue("Echo", "1.0", "2.0", "modrinth", "PENDING_RESTART", "Admin");

        // Paper spielt plugins/update/Echo.jar beim Serverneustart ein
        Files.move(sr.stagedFile(), installed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        List<PostRestartVerifier.Report> reports = PostRestartVerifier.verify(tx, pluginsDir, updateDir, log, queue);
        assertEquals(1, reports.size());
        assertEquals(PostRestartVerifier.Status.APPLIED, reports.get(0).status());
        assertTrue(tx.all().isEmpty());
        assertFalse(new UpdateQueueStore(qFile, log).contains("Echo"));
    }

    @Test
    void restartResetsAmbiguousMarkerWhilePreservingSnoozeAndChooseNoneUpdatesSnapshotSafely() throws Exception {
        ScamLog log = new ScamLog(Logger.getLogger("QueueAmbiguousRestartTest"), null);
        ConfigManager cfg = loadConfig("config-version: 2\ncrafty:\n  enabled: false\nmatching:\n  auto-assign-single-match: false\n");
        Path qFile = dir.resolve("queue-ambig.yml");
        UpdateQueueStore store = new UpdateQueueStore(qFile, log);
        QueueService service = new QueueService(store, cfg, log);
        MatchStore matchStore = new MatchStore(dir.resolve("matches-ambig.yml"), log);
        HttpService http = new HttpService("ScamUpdates-Test", Duration.ofSeconds(3), 0, false, log);
        SourceRegistry reg = new SourceRegistry(http, cfg);
        MatchService matches = new MatchService(cfg, reg, matchStore, log);

        PluginInfo alpha = new PluginInfo("Alpha", "1.0", List.of(), null,
                "https://modrinth.com/plugin/alpha https://hangar.papermc.io/org/alpha", null, dir.resolve("Alpha.jar"));
        assertEquals(MatchService.Type.PENDING, matches.resolve(alpha).type());

        store.enqueueIfAbsent("Alpha", "1.0", "", "AMBIGUOUS", "Admin");
        store.enqueueIfAbsent("Beta", "1.0", "modrinth", "SNOOZED", "Admin");
        store.markNotified("Alpha", "AMBIGUOUS");
        store.markNotified("Beta", "2.0");
        store.snooze("Beta", 9_999_999_999_999L);

        // Serverneustart: neue Store-Instanz + resetAmbiguousNotifications()
        UpdateQueueStore reloaded = new UpdateQueueStore(qFile, log);
        assertEquals(1, reloaded.resetAmbiguousNotifications());
        assertEquals("", reloaded.get("Alpha").orElseThrow().notifiedVersion());
        assertEquals("2.0", reloaded.get("Beta").orElseThrow().notifiedVersion());
        assertEquals(9_999_999_999_999L, reloaded.get("Beta").orElseThrow().snoozedUntil());

        // Nach dem Neustart muss Alpha wieder als ausstehende AMBIGUOUS-Benachrichtigung erscheinen
        QueueService reloadedSvc = new QueueService(reloaded, cfg, log);
        UpdateService.Snapshot snap = new UpdateService.Snapshot(1000L, List.of(
                new UpdateService.PluginStatus(alpha, UpdateService.Kind.PENDING_MATCH, null, null, null)
        ));
        List<QueueService.EvaluatedEntry> due = reloadedSvc.pendingNotifications(snap, matches, Set.of("Alpha", "Beta"), 2000L);
        assertEquals(1, due.size());
        assertEquals("Alpha", due.get(0).entry().plugin());
        assertEquals(QueueService.EntryState.AMBIGUOUS, due.get(0).state());

        // clearPendingMatch & markExcluded aktualisieren den UpdateService-Snapshot konsistent ohne Update-Download
        UpdateService us = new UpdateService(null, cfg, new StateManager(), log, http, reg, matches,
                new TransactionStore(dir.resolve("tx-ambig.yml"), log),
                new Stager(dir.resolve("upd-ambig"), new BackupManager(dir.resolve("bk-ambig"), log)),
                new OperationLock(), Executors.newSingleThreadExecutor(), dir, dir.resolve("tmp-ambig"),
                new OperationRegistry(dir.resolve("ops-ambig.yml"), log), null);
        // "Keins davon" ueber QueueService -> kein Download/Transaction
        assertEquals(MatchService.PickResult.EXCLUDED, reloadedSvc.chooseNone("Alpha", matches, "Admin"));
        assertEquals(MatchStore.Reason.NONE_OF_THESE, matchStore.exclusion("Alpha").orElseThrow().reason());
        assertEquals(QueueService.EntryState.EXCLUDED.name(), reloaded.get("Alpha").orElseThrow().lastStatus());
        us.clearPendingMatch("Alpha");
        us.markExcluded("Alpha");
    }
}
