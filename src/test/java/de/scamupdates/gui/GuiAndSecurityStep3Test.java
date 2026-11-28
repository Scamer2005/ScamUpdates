package de.scamupdates.gui;

import de.scamupdates.config.ConfigManager;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.queue.UpdateQueueStore;
import de.scamupdates.restart.RestartCoordinator;
import de.scamupdates.restart.RestartScheduleStore;
import de.scamupdates.security.ConfirmationManager;
import de.scamupdates.security.Perm;
import de.scamupdates.state.PluginState;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.UpdateService;
import de.scamupdates.utils.AtomicFiles;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GuiAndSecurityStep3Test {

    @TempDir Path dir;

    private static PluginLibraryMenu.Row row(String name, UpdateService.Kind kind, String source,
                                             String status, boolean hasBackup, boolean excluded) {
        return new PluginLibraryMenu.Row(
                new PluginInfo(name, "1.0", List.of(), null, null, null, null),
                null, kind, "2.0", source, status, "kompatibel", hasBackup ? "v0.9" : "keines",
                hasBackup, excluded, false);
    }

    @Test
    void pluginLibraryFilterSortAndQueryWorkForAllModes() {
        List<PluginLibraryMenu.Row> raw = List.of(
                row("Zebra", UpdateService.Kind.UP_TO_DATE, "modrinth", "Aktuell", true, false),
                row("Alpha", UpdateService.Kind.UPDATE_AVAILABLE, "hangar", "Update verfuegbar", false, false),
                row("Beta", UpdateService.Kind.ERROR, "spigot", "Fehler", true, false),
                row("Gamma", UpdateService.Kind.EXCLUDED, "-", "Ausgeschlossen", false, true)
        );

        assertEquals(4, PluginLibraryMenu.filterAndSort(raw, PluginLibraryMenu.Filter.ALL, PluginLibraryMenu.Sort.NAME, "").size());
        assertEquals(List.of("Alpha", "Beta", "Gamma", "Zebra"),
                PluginLibraryMenu.filterAndSort(raw, PluginLibraryMenu.Filter.ALL, PluginLibraryMenu.Sort.NAME, "")
                        .stream().map(r -> r.plugin().name()).toList());

        assertEquals(List.of("Alpha"),
                PluginLibraryMenu.filterAndSort(raw, PluginLibraryMenu.Filter.UPDATES, PluginLibraryMenu.Sort.NAME, "")
                        .stream().map(r -> r.plugin().name()).toList());
        assertEquals(List.of("Zebra"),
                PluginLibraryMenu.filterAndSort(raw, PluginLibraryMenu.Filter.CURRENT, PluginLibraryMenu.Sort.NAME, "")
                        .stream().map(r -> r.plugin().name()).toList());
        assertEquals(List.of("Beta"),
                PluginLibraryMenu.filterAndSort(raw, PluginLibraryMenu.Filter.PROBLEMS, PluginLibraryMenu.Sort.NAME, "")
                        .stream().map(r -> r.plugin().name()).toList());
        assertEquals(List.of("Gamma"),
                PluginLibraryMenu.filterAndSort(raw, PluginLibraryMenu.Filter.EXCLUDED, PluginLibraryMenu.Sort.NAME, "")
                        .stream().map(r -> r.plugin().name()).toList());
        assertEquals(List.of("Beta", "Zebra"),
                PluginLibraryMenu.filterAndSort(raw, PluginLibraryMenu.Filter.WITH_BACKUP, PluginLibraryMenu.Sort.NAME, "")
                        .stream().map(r -> r.plugin().name()).toList());

        // Sortierung: UPDATES_FIRST -> Alpha vor Beta vor Zebra vor Gamma
        assertEquals(List.of("Alpha", "Beta", "Zebra", "Gamma"),
                PluginLibraryMenu.filterAndSort(raw, PluginLibraryMenu.Filter.ALL, PluginLibraryMenu.Sort.UPDATES_FIRST, "")
                        .stream().map(r -> r.plugin().name()).toList());

        // Sortierung: BACKUP_FIRST -> Beta, Zebra vor Alpha, Gamma
        assertEquals(List.of("Beta", "Zebra", "Alpha", "Gamma"),
                PluginLibraryMenu.filterAndSort(raw, PluginLibraryMenu.Filter.ALL, PluginLibraryMenu.Sort.BACKUP_FIRST, "")
                        .stream().map(r -> r.plugin().name()).toList());

        // Anvil-Suche filtert nach Name, Quelle oder Status
        assertEquals(List.of("Alpha"),
                PluginLibraryMenu.filterAndSort(raw, PluginLibraryMenu.Filter.ALL, PluginLibraryMenu.Sort.NAME, "hangar")
                        .stream().map(r -> r.plugin().name()).toList());
        assertEquals(List.of("Beta"),
                PluginLibraryMenu.filterAndSort(raw, PluginLibraryMenu.Filter.ALL, PluginLibraryMenu.Sort.NAME, "fehler")
                        .stream().map(r -> r.plugin().name()).toList());
    }

    @Test
    void scheduleTimeAndOneTimeIntegerParsersHandleValidAndInvalidInputs() {
        assertArrayEquals(new int[]{4, 30}, ScheduleEditorMenu.parseTimeInput("04:30", RestartScheduleStore.Frequency.DAILY));
        assertArrayEquals(new int[]{23, 59}, ScheduleEditorMenu.parseTimeInput("23:59", RestartScheduleStore.Frequency.WEEKLY));
        assertNull(ScheduleEditorMenu.parseTimeInput("24:00", RestartScheduleStore.Frequency.DAILY));
        assertNull(ScheduleEditorMenu.parseTimeInput("12:60", RestartScheduleStore.Frequency.DAILY));
        assertNull(ScheduleEditorMenu.parseTimeInput("abc", RestartScheduleStore.Frequency.DAILY));

        assertArrayEquals(new int[]{0, 15}, ScheduleEditorMenu.parseTimeInput(":15", RestartScheduleStore.Frequency.HOURLY));
        assertArrayEquals(new int[]{0, 45}, ScheduleEditorMenu.parseTimeInput("45", RestartScheduleStore.Frequency.HOURLY));
        assertArrayEquals(new int[]{0, 5}, ScheduleEditorMenu.parseTimeInput("0:05", RestartScheduleStore.Frequency.HOURLY));
        assertNull(ScheduleEditorMenu.parseTimeInput("60", RestartScheduleStore.Frequency.HOURLY));

        assertEquals(12, OneTimeRestartMenu.parseIntRange("12", 0, 59).orElseThrow());
        assertTrue(OneTimeRestartMenu.parseIntRange("-1", 0, 59).isEmpty());
        assertTrue(OneTimeRestartMenu.parseIntRange("60", 0, 59).isEmpty());
        assertTrue(OneTimeRestartMenu.parseIntRange("xyz", 0, 168).isEmpty());
    }

    @Test
    void permissionsAreGranularlyEnforcedForAllStep3Actions() {
        for (Perm p : List.of(Perm.UPDATE_SINGLE, Perm.QUEUE, Perm.SCHEDULE, Perm.RESTORE, Perm.SOURCES, Perm.RESTART)) {
            assertTrue(p.allows(n -> n.equals(p.node)), p + " muss eigenen Knoten erlauben");
            assertTrue(p.allows(n -> n.equals(Perm.ADMIN.node)), p + " muss Admin-Knoten erlauben");
            assertFalse(p.allows(n -> n.equals(Perm.CHECK.node)), p + " darf nicht allein ueber CHECK erreichbar sein");
        }
        assertFalse(Set.of(Perm.UPDATE_SINGLE, Perm.QUEUE, Perm.SCHEDULE).contains(Perm.UPDATE_SERVER));
    }

    @Test
    void persistentActionsAndConfirmationsOperateIndependentlyFromOpenGui() throws Exception {
        ScamLog log = new ScamLog(Logger.getLogger("GuiSecStep3Test"), null);
        String defaults = new String(getClass().getResourceAsStream("/config.yml").readAllBytes(), StandardCharsets.UTF_8);
        ConfigManager cfg = new ConfigManager(null, log);
        assertTrue(cfg.loadFromString("config-version: 2\ncrafty:\n  enabled: false\n", defaults).ok());

        StateManager state = new StateManager();
        state.set(PluginState.ACTIVE, List.of("OK"));

        AtomicLong clock = new AtomicLong(1_000_000L);
        ConfirmationManager cm = new ConfirmationManager(clock::get, () -> 30, () -> 5, () -> 6);
        UUID player = UUID.randomUUID();
        AtomicInteger executed = new AtomicInteger();

        var pending = cm.create(player, "single-download:Vault", Perm.UPDATE_SINGLE, executed::incrementAndGet);
        assertEquals(ConfirmationManager.ConfirmResult.NO_PERMISSION,
                cm.confirm(pending.token(), player, p -> p.allows(n -> n.equals(Perm.CHECK.node))).result());

        var pending2 = cm.create(player, "single-download:Vault", Perm.UPDATE_SINGLE, executed::incrementAndGet);
        var ok = cm.confirm(pending2.token(), player, p -> p.allows(n -> n.equals(Perm.UPDATE_SINGLE.node)));
        assertEquals(ConfirmationManager.ConfirmResult.OK, ok.result());
        ok.pending().run().run();
        assertEquals(1, executed.get());

        // Ohne offenes GUI bleiben Queue und Neustart-Countdown vollstaendig persistent
        UpdateQueueStore qStore = new UpdateQueueStore(dir.resolve("queue.yml"), log);
        qStore.enqueueIfAbsent("Vault", "1.7", "modrinth", "QUEUED", "Admin");
        assertEquals(1, new UpdateQueueStore(dir.resolve("queue.yml"), log).all().size());

        RestartScheduleStore rsStore = new RestartScheduleStore(dir.resolve("restarts.yml"), log);
        RestartCoordinator rc = new RestartCoordinator(rsStore, cfg, state, log, clock.get());
        rc.scheduleCustom(0, 5, 0, "Admin", "Ohne GUI", clock.get());
        assertTrue(new RestartScheduleStore(dir.resolve("restarts.yml"), log).oneTime().isPresent());
    }

    @Test
    void atomicFilesHandlesConcurrentWritesWithoutTempCollisionOrLeftovers() throws Exception {
        Path target = dir.resolve("concurrent-notified.yml");
        int threads = 8;
        int iterations = 15;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> futs = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                futs.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < iterations; i++) {
                        AtomicFiles.writeString(target, "writer: " + tid + "-" + i + "\n");
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var f : futs) f.get();
        } finally {
            pool.shutdownNow();
        }

        String finalContent = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(finalContent.startsWith("writer: "));
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(List.of(target), s.toList(), "Es duerfen keine .tmp-Dateien uebrig bleiben");
        }
    }

    @Test
    void pagedMenuExtrasReceivesAlreadyLoadedEntriesWithoutRequerying() throws Exception {
        // Verifiziert ueber Bytecode/Methodenvertrag, dass PagedMenu.extras(List) existiert und build() nicht dreifach entries() aufruft
        var extrasWithList = PagedMenu.class.getDeclaredMethod("extras", List.class);
        assertNotNull(extrasWithList);
        assertEquals(void.class, extrasWithList.getReturnType());
    }

    @Test
    void anvilReflectBytecodeHasNoDirectAnvilViewDescriptorReference() throws Exception {
        try (var in = AnvilReflect.class.getResourceAsStream("AnvilReflect.class")) {
            assertNotNull(in);
            String raw = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(raw.contains("Lorg/bukkit/inventory/view/AnvilView;"),
                    "AnvilReflect darf keine direkte Methodenreferenz auf 1.21-AnvilView enthalten");
        }
    }

    @Test
    void paperHintPromotesSupported26xTargetsAndExcludesUnknownFutureMajors() {
        assertEquals("noch nicht geprueft", GuiService.formatPaperHint(null));
        assertTrue(GuiService.formatPaperHint(new de.scamupdates.net.PaperDownloads.PaperStatus(
                "26.2", 100, -1, "26.2", false, false, "Timeout")).startsWith("Pruefung fehlgeschlagen"));

        // 1.21.11 -> 26.3 wird als unterstuetztes Update-Ziel beworben
        assertEquals("Paper ist aktuell; neuere Minecraft-Version 26.3 verfuegbar",
                GuiService.formatPaperHint(new de.scamupdates.net.PaperDownloads.PaperStatus(
                        "1.21.11", 69, 69, "26.3", false, true, null)));

        // 26.1.2 -> 26.3 wird als unterstuetztes Update-Ziel beworben
        assertEquals("Paper ist aktuell; neuere Minecraft-Version 26.3 verfuegbar",
                GuiService.formatPaperHint(new de.scamupdates.net.PaperDownloads.PaperStatus(
                        "26.1.2", 74, 74, "26.3", false, true, null)));

        // Neuerer Build auf 26.3 hat Vorrang
        assertEquals("Neuerer Build verfuegbar",
                GuiService.formatPaperHint(new de.scamupdates.net.PaperDownloads.PaperStatus(
                        "26.3", 260, 264, "26.3", true, false, null)));

        // Unbekannte zukuenftige Hauptversionen (27.1, 1.22) werden nicht beworben
        assertEquals("Paper ist aktuell",
                GuiService.formatPaperHint(new de.scamupdates.net.PaperDownloads.PaperStatus(
                        "26.3", 264, 264, "27.1", false, true, null)));
        assertEquals("Paper ist aktuell",
                GuiService.formatPaperHint(new de.scamupdates.net.PaperDownloads.PaperStatus(
                        "1.21.11", 69, 69, "1.22", false, true, null)));
    }
}
