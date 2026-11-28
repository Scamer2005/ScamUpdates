package de.scamupdates.restart;

import de.scamupdates.backup.BackupManager;
import de.scamupdates.config.ConfigManager;
import de.scamupdates.history.HistoryEntry;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.security.OperationLock;
import de.scamupdates.state.PluginState;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.TransactionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RestartCoordinatorTest {

    @TempDir Path dir;

    private ConfigManager loadConfig(String extraYaml) throws Exception {
        String defaults = new String(getClass().getResourceAsStream("/config.yml").readAllBytes(), StandardCharsets.UTF_8);
        ScamLog log = new ScamLog(Logger.getLogger("RestartCoordTest"), null);
        ConfigManager cfg = new ConfigManager(null, log);
        assertTrue(cfg.loadFromString(extraYaml, defaults).ok());
        return cfg;
    }

    private RestartService buildLocalRestartService(ConfigManager cfg, StateManager state, ScamLog log,
                                                    ExecutorService io, boolean scriptExists, AtomicInteger triggerCount) throws Exception {
        Path root = dir.resolve("server");
        Files.createDirectories(root);
        if (scriptExists) {
            Files.writeString(root.resolve("start.sh"), "#!/bin/sh\n");
        }
        TransactionStore tx = new TransactionStore(dir.resolve("tx.yml"), log);
        BackupManager backups = new BackupManager(dir.resolve("backups"), log);
        RestartService rs = new RestartService(state, cfg, log, new OperationLock(), io, tx, backups,
                dir.resolve("update"), () -> null, () -> "1.21.11");
        if (scriptExists) {
            rs.setLocal(new LocalRestart(root, "restart", () -> true, cmd -> {
                triggerCount.incrementAndGet();
                return true;
            }), Runnable::run);
        }
        return rs;
    }

    @Test
    void allSevenPresetsWorkAndImmediateSkipsWarningPhase() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            ScamLog log = new ScamLog(Logger.getLogger("PresetTest"), null);
            ConfigManager cfg = loadConfig("config-version: 2\ncrafty:\n  enabled: false\n");
            StateManager state = new StateManager();
            state.set(PluginState.ACTIVE, List.of("OK"));
            AtomicInteger triggers = new AtomicInteger();
            RestartService rs = buildLocalRestartService(cfg, state, log, io, true, triggers);
            RestartScheduleStore store = new RestartScheduleStore(dir.resolve("restarts.yml"), log);
            long t0 = 1_000_000_000L;
            RestartCoordinator rc = new RestartCoordinator(store, cfg, state, log, t0);
            rc.setRestartService(rs);

            List<Integer> warnings = new ArrayList<>();
            rc.setListener(new RestartCoordinator.Listener() {
                @Override public void onWarning(RestartCoordinator.ActiveCountdown cd, int remainingSeconds) { warnings.add(remainingSeconds); }
            });

            // 6 verzoegerte Presets testen
            for (RestartCoordinator.Preset p : RestartCoordinator.Preset.values()) {
                if (p == RestartCoordinator.Preset.IMMEDIATE) continue;
                rc.schedulePreset(p, "Admin", p.displayName, t0).join();
                RestartCoordinator.ActiveCountdown active = rc.activeCountdown().orElseThrow();
                assertEquals(p.seconds, active.remainingSeconds(t0));
                assertEquals(active.id(), store.oneTime().orElseThrow().id());
            }

            // Sofortiges Preset bricht aktiven Countdown ab und loest ohne Warnungen direkt aus
            RestartService.Outcome out = rc.schedulePreset(RestartCoordinator.Preset.IMMEDIATE, "Admin", "Sofort", t0).join();
            assertEquals(RestartService.Result.REQUESTED_LOCAL, out.result());
            assertEquals(1, triggers.get());
            assertTrue(warnings.isEmpty());
            assertTrue(rc.activeCountdown().isEmpty());
            assertTrue(store.oneTime().isEmpty());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void customScheduleValidationReplacesPreviousAndCancelNotifies() throws Exception {
        ScamLog log = new ScamLog(Logger.getLogger("CustomRestartTest"), null);
        ConfigManager cfg = loadConfig("config-version: 2\ncrafty:\n  enabled: false\n");
        StateManager state = new StateManager();
        state.set(PluginState.ACTIVE, List.of("OK"));
        RestartScheduleStore store = new RestartScheduleStore(dir.resolve("restarts.yml"), log);
        long t0 = 5_000_000L;
        RestartCoordinator rc = new RestartCoordinator(store, cfg, state, log, t0);

        AtomicReference<String> cancelledActor = new AtomicReference<>();
        rc.setListener(new RestartCoordinator.Listener() {
            @Override public void onCancelled(RestartCoordinator.ActiveCountdown prev, String actor) { cancelledActor.set(actor); }
        });

        assertThrows(IllegalArgumentException.class, () -> rc.scheduleCustom(0, 0, 0, "Admin", "", t0));
        assertThrows(IllegalArgumentException.class, () -> rc.scheduleCustom(-1, 5, 0, "Admin", "", t0));

        RestartCoordinator.ActiveCountdown first = rc.scheduleCustom(1, 2, 3, "Admin", "Wartung", t0);
        assertEquals(3723L, first.remainingSeconds(t0));

        RestartCoordinator.ActiveCountdown second = rc.scheduleCustom(0, 0, 45, "Admin2", "Neu", t0);
        assertNotEquals(first.id(), second.id());
        assertEquals(45L, rc.activeCountdown().orElseThrow().remainingSeconds(t0));
        assertEquals(second.id(), store.oneTime().orElseThrow().id());

        assertTrue(rc.cancelActive("Admin2"));
        assertEquals("Admin2", cancelledActor.get());
        assertTrue(rc.activeCountdown().isEmpty());
        assertTrue(store.oneTime().isEmpty());
        assertFalse(rc.cancelActive("Admin2"));
    }

    @Test
    void configurableWarningsFireOnceInDescendingOrderAndTriggerRestart() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            ScamLog log = new ScamLog(Logger.getLogger("WarnTest"), null);
            ConfigManager cfg = loadConfig("""
                    config-version: 2
                    crafty:
                      enabled: false
                    restarts:
                      warning-seconds: [60, 15, 5, 1]
                    """);
            StateManager state = new StateManager();
            state.set(PluginState.ACTIVE, List.of("OK"));
            AtomicInteger triggers = new AtomicInteger();
            RestartService rs = buildLocalRestartService(cfg, state, log, io, true, triggers);
            RestartScheduleStore store = new RestartScheduleStore(dir.resolve("restarts.yml"), log);
            long t0 = 10_000_000L;
            RestartCoordinator rc = new RestartCoordinator(store, cfg, state, log, t0);
            rc.setRestartService(rs);

            List<Integer> warnings = new ArrayList<>();
            rc.setListener(new RestartCoordinator.Listener() {
                @Override public void onWarning(RestartCoordinator.ActiveCountdown cd, int remainingSeconds) { warnings.add(remainingSeconds); }
            });

            rc.scheduleCustom(0, 1, 5, "Admin", "Test", t0); // 65s
            rc.tick(t0 + 1_000L);  // 64s -> keine Warnung
            assertTrue(warnings.isEmpty());
            rc.tick(t0 + 5_000L);  // 60s -> 60
            rc.tick(t0 + 5_000L);  // doppeltes Tick in derselben Sekunde darf 60 nicht doppelt ausloesen
            rc.tick(t0 + 52_000L); // 13s -> 15
            rc.tick(t0 + 62_000L); // 3s -> 5
            rc.tick(t0 + 64_000L); // 1s -> 1
            assertEquals(List.of(60, 15, 5, 1), warnings);
            assertEquals(0, triggers.get());

            rc.tick(t0 + 65_000L); // 0s -> Ausfuehrung
            Thread.sleep(100);
            assertEquals(1, triggers.get());
            assertTrue(rc.activeCountdown().isEmpty());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void reconcileOnStartupResumesFutureWithoutReplayingElapsedWarningsAndDiscardsExpired() throws Exception {
        ScamLog log = new ScamLog(Logger.getLogger("ReconcileTest"), null);
        ConfigManager cfg = loadConfig("""
                config-version: 2
                crafty:
                  enabled: false
                restarts:
                  warning-seconds: [60, 30, 10, 5]
                """);
        StateManager state = new StateManager();
        state.set(PluginState.ACTIVE, List.of("OK"));
        Path file = dir.resolve("restarts.yml");

        // 1. Zukuenftiger einmaliger Neustart (verbleibend 25s -> 60 und 30 bereits uebersprungen)
        RestartScheduleStore store1 = new RestartScheduleStore(file, log);
        store1.scheduleOneTime(125_000L, 60_000L, "Admin", "Restart", false);

        RestartCoordinator rc1 = new RestartCoordinator(new RestartScheduleStore(file, log), cfg, state, log, 100_000L);
        List<Integer> warnings = new ArrayList<>();
        rc1.setListener(new RestartCoordinator.Listener() {
            @Override public void onWarning(RestartCoordinator.ActiveCountdown cd, int sec) { warnings.add(sec); }
        });
        rc1.reconcileOnStartup(100_000L);
        assertTrue(rc1.activeCountdown().isPresent());
        rc1.tick(101_000L); // verbleibend 24s -> 60/30 wurden uebersprungen!
        assertTrue(warnings.isEmpty(), "Bereits verstrichene Warnungen duerfen nach Neustart nicht erneut gesendet werden");
        rc1.tick(116_000L); // verbleibend 9s -> 10
        assertEquals(List.of(10), warnings);

        // 2. Abgelaufener einmaliger Neustart wird sauber bereinigt
        RestartScheduleStore store2 = new RestartScheduleStore(dir.resolve("restarts-expired.yml"), log);
        store2.scheduleOneTime(50_000L, 10_000L, "Admin", "Alt", false);
        RestartCoordinator rc2 = new RestartCoordinator(new RestartScheduleStore(dir.resolve("restarts-expired.yml"), log),
                cfg, state, log, 90_000L);
        rc2.reconcileOnStartup(90_000L);
        assertTrue(rc2.activeCountdown().isEmpty());
        assertTrue(new RestartScheduleStore(dir.resolve("restarts-expired.yml"), log).oneTime().isEmpty());
    }

    @Test
    void readinessFailureNotifiesAndLogsWithoutFakingSuccessAndRecurringRuleFiresOncePerSlot() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            ScamLog log = new ScamLog(Logger.getLogger("ReadinessFailTest"), null);
            // Crafty enabled mit ungueltiger Config -> CraftyClient == null -> readiness().ready() == false
            ConfigManager brokenCraftyCfg = loadConfig("""
                    config-version: 2
                    crafty:
                      enabled: true
                    restarts:
                      warning-seconds: [30, 10]
                    """);
            StateManager state = new StateManager();
            state.set(PluginState.ACTIVE, List.of("OK"));
            HistoryStore history = new HistoryStore(dir.resolve("history.jsonl"), log, () -> 30, () -> 100);

            RestartService notReadyRs = new RestartService(state, brokenCraftyCfg, log, new OperationLock(), io,
                    new TransactionStore(dir.resolve("tx.yml"), log), new BackupManager(dir.resolve("bk"), log),
                    dir.resolve("upd"), () -> null, () -> "1.21.11");
            assertFalse(notReadyRs.readiness().ready());

            RestartScheduleStore store = new RestartScheduleStore(dir.resolve("restarts-sched.yml"), log);
            var rule = store.addRule("Nacht", RestartScheduleStore.Frequency.DAILY, java.util.Set.of(), 4, 0, false);

            ZonedDateTime start = ZonedDateTime.of(2026, 6, 15, 3, 59, 0, 0, RestartScheduleStore.DEFAULT_ZONE);
            long tStart = start.toInstant().toEpochMilli();
            long tTrigger = start.plusMinutes(1).toInstant().toEpochMilli();

            RestartCoordinator rc = new RestartCoordinator(store, brokenCraftyCfg, state, log, tStart);
            rc.setRestartService(notReadyRs);
            rc.setHistory(history);

            List<Integer> warnings = new ArrayList<>();
            AtomicReference<String> failedDetail = new AtomicReference<>();
            rc.setListener(new RestartCoordinator.Listener() {
                @Override public void onWarning(RestartCoordinator.ActiveCountdown cd, int sec) { warnings.add(sec); }
                @Override public void onExecutionFailed(String actor, String detail) { failedDetail.set(detail); }
            });

            // Im Warnfenster (genau bei 30s, dann 10s) -> Regel wird einmalig scharf geschaltet und warnt
            rc.tick(tTrigger - 30_000L);
            assertEquals(rule.id(), rc.activeCountdown().orElseThrow().ruleId());
            rc.tick(tTrigger - 10_000L);
            assertEquals(List.of(30, 10), warnings);

            // Am Zielzeitpunkt -> Readiness schlaegt sauber fehl und wird protokolliert
            rc.tick(tTrigger);
            assertNotNull(failedDetail.get());
            List<HistoryEntry> hist = history.list(HistoryStore.Sort.NEWEST);
            assertTrue(hist.stream().anyMatch(e -> "restart-scheduled".equals(e.action()) && "FAILED".equals(e.result())));

            // Weitere Ticks kurz danach duerfen denselben Slot nicht noch einmal starten
            rc.tick(tTrigger + 500L);
            assertTrue(rc.activeCountdown().isEmpty());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void oneTimeAlwaysOverridesScheduledDisplacesSlotLogsCancelAndControlsNextEffectiveTrigger() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            ScamLog log = new ScamLog(Logger.getLogger("PriorityRuleTest"), null);
            ConfigManager cfg = loadConfig("""
                    config-version: 2
                    crafty:
                      enabled: false
                    restarts:
                      warning-seconds: [60, 10]
                    """);
            StateManager state = new StateManager();
            state.set(PluginState.ACTIVE, List.of("OK"));
            HistoryStore history = new HistoryStore(dir.resolve("history-prio.jsonl"), log, () -> 30, () -> 100);
            AtomicInteger triggers = new AtomicInteger();
            RestartService rs = buildLocalRestartService(cfg, state, log, io, true, triggers);
            RestartScheduleStore store = new RestartScheduleStore(dir.resolve("restarts-prio.yml"), log);
            store.addRule("Nacht", RestartScheduleStore.Frequency.DAILY, java.util.Set.of(), 4, 0, false);

            ZonedDateTime start = ZonedDateTime.of(2027, 6, 15, 3, 59, 20, 0, RestartScheduleStore.DEFAULT_ZONE);
            long tStart = start.toInstant().toEpochMilli();
            long tDailySlot = start.withHour(4).withMinute(0).withSecond(0).toInstant().toEpochMilli();

            RestartCoordinator rc = new RestartCoordinator(store, cfg, state, log, tStart);
            rc.setRestartService(rs);
            rc.setHistory(history);

            List<RestartCoordinator.ActiveCountdown> cancelledCountdowns = new ArrayList<>();
            rc.setListener(new RestartCoordinator.Listener() {
                @Override public void onCancelled(RestartCoordinator.ActiveCountdown prev, String actor) { cancelledCountdowns.add(prev); }
            });

            // 1. Tick aktiviert den SCHEDULED-Countdown um 04:00
            rc.tick(tStart);
            assertEquals(RestartCoordinator.Source.SCHEDULED, rc.activeCountdown().orElseThrow().source());
            assertEquals(tDailySlot, rc.nextEffectiveTriggerMillis(tStart).orElseThrow());

            // 2. Einmaliger Neustart in 5 Minuten verdraengt aktiven SCHEDULED-Countdown
            RestartCoordinator.ActiveCountdown ot = rc.scheduleDelayed(300, "Admin", "Wartung", tStart);
            assertEquals(RestartCoordinator.Source.ONE_TIME, rc.activeCountdown().orElseThrow().source());
            assertEquals(ot.targetMillis(), rc.nextEffectiveTriggerMillis(tStart).orElseThrow());
            assertEquals(1, cancelledCountdowns.size());
            assertEquals(RestartCoordinator.Source.SCHEDULED, cancelledCountdowns.get(0).source());
            assertTrue(history.list(HistoryStore.Sort.NEWEST).stream()
                    .anyMatch(e -> "restart-cancel".equals(e.action()) && "Admin".equals(e.actor())));

            // 3. Zu 04:00:00 bleibt ONE_TIME aktiv und verdraengter SCHEDULED-Slot loest NICHT doppelt aus
            rc.tick(tDailySlot);
            assertEquals(0, triggers.get());
            assertEquals(RestartCoordinator.Source.ONE_TIME, rc.activeCountdown().orElseThrow().source());

            // 4. Wird ONE_TIME abgebrochen, springt nextEffectiveTriggerMillis auf den morgigen 04:00-Slot (heutiger Slot bleibt verdraengt)
            assertTrue(rc.cancelActive("Admin"));
            long expectedTomorrow = start.plusDays(1).withHour(4).withMinute(0).withSecond(0).toInstant().toEpochMilli();
            assertEquals(expectedTomorrow, rc.nextEffectiveTriggerMillis(tStart + 5_000L).orElseThrow());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void coordinatorTickReconcileAndReloadHandle2027DstAndConfigChangesSafely() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            ScamLog log = new ScamLog(Logger.getLogger("CoordDst2027Test"), null);
            ConfigManager cfg = loadConfig("""
                    config-version: 2
                    crafty:
                      enabled: false
                    restarts:
                      timezone: Europe/Berlin
                      warning-seconds: [60, 10]
                    """);
            StateManager state = new StateManager();
            state.set(PluginState.ACTIVE, List.of("OK"));
            AtomicInteger triggers = new AtomicInteger();
            RestartService rs = buildLocalRestartService(cfg, state, log, io, true, triggers);

            // 1. Herbstumstellung 31.10.2027: DAILY 02:30 darf genau einmal (02:30+02:00) ausloesen, HOURLY :15 dreimal
            RestartScheduleStore dailyStore = new RestartScheduleStore(dir.resolve("restarts-dst-daily.yml"), log);
            dailyStore.addRule("Daily0230", RestartScheduleStore.Frequency.DAILY, java.util.Set.of(), 2, 30, false);
            ZonedDateTime first0230 = ZonedDateTime.of(2027, 10, 31, 2, 30, 0, 0, RestartScheduleStore.DEFAULT_ZONE).withEarlierOffsetAtOverlap();
            ZonedDateTime second0230 = first0230.withLaterOffsetAtOverlap();

            RestartCoordinator rcDaily = new RestartCoordinator(dailyStore, cfg, state, log, first0230.minusMinutes(2).toInstant().toEpochMilli());
            rcDaily.setRestartService(rs);
            rcDaily.tick(first0230.minusSeconds(30).toInstant().toEpochMilli());
            assertTrue(rcDaily.activeCountdown().isPresent());
            rcDaily.tick(first0230.toInstant().toEpochMilli());
            Thread.sleep(60);
            assertEquals(1, triggers.get());
            rcDaily.tick(second0230.minusSeconds(30).toInstant().toEpochMilli());
            assertTrue(rcDaily.activeCountdown().isEmpty(), "Zweites 02:30 (+01:00) darf DAILY nicht nochmals ausloesen");
            rcDaily.tick(second0230.toInstant().toEpochMilli());
            Thread.sleep(60);
            assertEquals(1, triggers.get());

            // HOURLY :15 ueber alle 3 Herbst-Phasen (02:15+02:00, 02:15+01:00, 03:15+01:00)
            triggers.set(0);
            RestartScheduleStore hourlyStore = new RestartScheduleStore(dir.resolve("restarts-dst-hourly.yml"), log);
            hourlyStore.addRule("Hourly15", RestartScheduleStore.Frequency.HOURLY, java.util.Set.of(), 0, 15, false);
            ZonedDateTime h1 = ZonedDateTime.of(2027, 10, 31, 2, 15, 0, 0, RestartScheduleStore.DEFAULT_ZONE).withEarlierOffsetAtOverlap();
            ZonedDateTime h2 = h1.withLaterOffsetAtOverlap();
            ZonedDateTime h3 = h2.plusHours(1);
            RestartCoordinator rcHourly = new RestartCoordinator(hourlyStore, cfg, state, log, h1.minusSeconds(90).toInstant().toEpochMilli());
            rcHourly.setRestartService(rs);
            for (ZonedDateTime slot : List.of(h1, h2, h3)) {
                rcHourly.tick(slot.minusSeconds(30).toInstant().toEpochMilli());
                assertTrue(rcHourly.activeCountdown().isPresent());
                rcHourly.tick(slot.toInstant().toEpochMilli());
                Thread.sleep(50);
            }
            assertEquals(3, triggers.get());

            // 2. reconcileOnStartup ueber Fruehjahrs-Sprungstunde 28.03.2027 (01:55+01:00 -> 03:05+02:00 = exakt 10 Min.)
            Path recFile = dir.resolve("restarts-dst-rec.yml");
            ZonedDateTime recNow = ZonedDateTime.of(2027, 3, 28, 1, 55, 0, 0, RestartScheduleStore.DEFAULT_ZONE);
            ZonedDateTime recTarget = ZonedDateTime.of(2027, 3, 28, 3, 5, 0, 0, RestartScheduleStore.DEFAULT_ZONE);
            RestartScheduleStore recStore = new RestartScheduleStore(recFile, log);
            recStore.scheduleOneTime(recTarget.toInstant().toEpochMilli(), recNow.minusMinutes(5).toInstant().toEpochMilli(), "Admin", "DST", false);
            RestartCoordinator rcRec = new RestartCoordinator(new RestartScheduleStore(recFile, log), cfg, state, log, recNow.toInstant().toEpochMilli());
            rcRec.reconcileOnStartup(recNow.toInstant().toEpochMilli());
            assertEquals(600L, rcRec.activeCountdown().orElseThrow().remainingSeconds(recNow.toInstant().toEpochMilli()));

            // 3. onConfigReloaded: SCHEDULED wird bei Reload verworfen, restarts.enabled: false bricht aktiven Countdown ab
            RestartScheduleStore reloadStore = new RestartScheduleStore(dir.resolve("restarts-reload.yml"), log);
            reloadStore.addRule("Rule", RestartScheduleStore.Frequency.DAILY, java.util.Set.of(), 4, 0, false);
            RestartCoordinator rcReload = new RestartCoordinator(reloadStore, cfg, state, log, 1000L);
            rcReload.scheduleDelayed(300, "Admin", "Test", 1000L);
            assertTrue(rcReload.activeCountdown().isPresent());
            ConfigManager disabledCfg = loadConfig("config-version: 2\ncrafty:\n  enabled: false\nrestarts:\n  enabled: false\n");
            RestartCoordinator rcDisabled = new RestartCoordinator(reloadStore, disabledCfg, state, log, 1000L);
            rcDisabled.reconcileOnStartup(1000L);
            AtomicInteger cancels = new AtomicInteger();
            rcDisabled.setListener(new RestartCoordinator.Listener() {
                @Override public void onCancelled(RestartCoordinator.ActiveCountdown prev, String actor) { cancels.incrementAndGet(); }
            });
            rcDisabled.onConfigReloaded(1500L);
            assertTrue(rcDisabled.activeCountdown().isEmpty());
            assertEquals(1, cancels.get());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void concurrentExecutingGuardRejectsNewScheduleWithIllegalStateAndPreventsDoubleExecution() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        ExecutorService mainExec = Executors.newSingleThreadExecutor();
        try {
            ScamLog log = new ScamLog(Logger.getLogger("ExecGuardTest"), null);
            ConfigManager cfg = loadConfig("config-version: 2\ncrafty:\n  enabled: false\n");
            StateManager state = new StateManager();
            state.set(PluginState.ACTIVE, List.of("OK"));

            Path root = dir.resolve("server-lock");
            Files.createDirectories(root);
            Files.writeString(root.resolve("start.sh"), "#!/bin/sh\n");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger runs = new AtomicInteger();

            RestartService rs = new RestartService(state, cfg, log, new OperationLock(), io,
                    new TransactionStore(dir.resolve("tx-lock.yml"), log), new BackupManager(dir.resolve("bk-lock"), log),
                    dir.resolve("upd-lock"), () -> null, () -> "1.21.11");
            rs.setLocal(new LocalRestart(root, "restart", () -> true, cmd -> {
                runs.incrementAndGet();
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                return true;
            }), mainExec::execute);

            RestartCoordinator rc = new RestartCoordinator(new RestartScheduleStore(dir.resolve("restarts-lock.yml"), log),
                    cfg, state, log, 1000L);
            rc.setRestartService(rs);

            var fut = rc.triggerImmediate("Admin", "LockTest");
            assertTrue(entered.await(3, TimeUnit.SECONDS));

            // Waehrend Ausfuehrung: scheduleDelayed wirft IllegalStateException, triggerImmediate liefert BUSY
            assertThrows(IllegalStateException.class, () -> rc.scheduleDelayed(60, "Admin2", "Collision", 1100L));
            assertEquals(RestartService.Result.BUSY, rc.triggerImmediate("Admin2", "Collision").join().result());
            rc.tick(1200L);
            assertEquals(1, runs.get());

            release.countDown();
            assertEquals(RestartService.Result.REQUESTED_LOCAL, fut.join().result());
            assertDoesNotThrow(() -> rc.scheduleDelayed(60, "Admin2", "After", 2000L));
        } finally {
            io.shutdownNow();
            mainExec.shutdownNow();
        }
    }
}
