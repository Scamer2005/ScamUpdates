package de.scamupdates.queue;

import de.scamupdates.logging.ScamLog;
import de.scamupdates.model.Channel;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.model.RemoteRelease;
import de.scamupdates.model.UpdateCandidate;
import de.scamupdates.update.UpdateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class UpdateQueueStoreTest {

    @TempDir Path dir;

    private static UpdateService.PluginStatus status(String name, UpdateService.Kind kind, String toVersion) {
        PluginInfo pi = new PluginInfo(name, "1.0", List.of(), null, null, null, dirPathDummy(name));
        ProjectRef ref = new ProjectRef("modrinth", name.toLowerCase(), name, "https://example.org/" + name);
        RemoteRelease rel = toVersion == null ? null : new RemoteRelease(toVersion, Channel.STABLE, "https://example.org/" + name + ".jar", name + ".jar", Map.of(), -1, List.of(), false);
        return new UpdateService.PluginStatus(pi, kind, ref, rel, null);
    }

    private static Path dirPathDummy(String name) {
        return Path.of("plugins/" + name + ".jar");
    }

    @Test
    void queuePersistsAcrossReloadAndResolvesOnlyUpdateCandidates() {
        ScamLog log = new ScamLog(Logger.getLogger("QueueTest"), null);
        Path file = dir.resolve("queue.yml");

        UpdateQueueStore store = new UpdateQueueStore(file, log);
        store.enqueue("Vault", "1.7.4", "modrinth", "AdminA");
        store.enqueue("LuckPerms", "5.4.145", "modrinth", "AdminA");
        store.enqueue("vault", "1.7.5", "hangar", "AdminB"); // case-insensitives Ueberschreiben

        assertEquals(2, store.all().size());
        assertTrue(store.contains("VAULT"));
        assertEquals("1.7.5", store.get("Vault").orElseThrow().targetVersion());

        UpdateQueueStore reloaded = new UpdateQueueStore(file, log);
        assertEquals(2, reloaded.all().size());
        assertEquals("hangar", reloaded.get("vault").orElseThrow().sourceId());

        UpdateService.Snapshot snap = new UpdateService.Snapshot(System.currentTimeMillis(), List.of(
                status("Vault", UpdateService.Kind.UPDATE_AVAILABLE, "1.7.5"),
                status("LuckPerms", UpdateService.Kind.UP_TO_DATE, "5.4.145"),
                status("Other", UpdateService.Kind.UPDATE_AVAILABLE, "2.0")
        ));

        List<UpdateCandidate> resolved = reloaded.resolveCandidates(snap);
        assertEquals(1, resolved.size());
        assertEquals("Vault", resolved.get(0).plugin().name());

        assertTrue(reloaded.remove("Vault"));
        assertFalse(reloaded.contains("Vault"));
        assertEquals(1, reloaded.removeAll(List.of("LuckPerms")));
        assertTrue(reloaded.all().isEmpty());

        reloaded.enqueue("Foo", "2.0", "github", "AdminA");
        assertEquals(1, reloaded.clear());
        assertTrue(new UpdateQueueStore(file, log).all().isEmpty());
    }
}
