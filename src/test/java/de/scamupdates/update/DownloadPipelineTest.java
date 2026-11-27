package de.scamupdates.update;

import de.scamupdates.TestJars;
import de.scamupdates.model.Channel;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.model.RemoteRelease;
import de.scamupdates.model.UpdateCandidate;
import de.scamupdates.utils.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Kernanforderung: fehlerhafte Downloads duerfen vorhandene Plugin-JARs niemals beschaedigen. */
class DownloadPipelineTest {
    @TempDir Path dir;
    Path pluginsDir, tmpDir, installed;
    byte[] installedBytes;

    @BeforeEach
    void setUp() throws Exception {
        pluginsDir = dir.resolve("plugins");
        tmpDir = dir.resolve("tmp");
        installed = TestJars.plugin(pluginsDir.resolve("Foo.jar"), "Foo", "1.0", "old");
        installedBytes = Files.readAllBytes(installed);
    }

    private UpdateCandidate candidate(String url, Map<String, String> hashes, Map<String, String> extra) {
        PluginInfo p = new PluginInfo("Foo", "1.0", List.of(), null, null, null, installed);
        ProjectRef ref = new ProjectRef("modrinth", "foo", "Foo", "https://x", extra);
        RemoteRelease rel = new RemoteRelease("2.0", Channel.STABLE, url, "Foo-2.0.jar", hashes, -1, List.of(), false);
        return new UpdateCandidate(p, ref, rel);
    }

    private void assertUntouched() throws IOException {
        assertArrayEquals(installedBytes, Files.readAllBytes(installed));
        try (Stream<Path> s = Files.exists(tmpDir) ? Files.list(tmpDir) : Stream.empty()) {
            assertEquals(0, s.count(), "Temp-Dateien muessen aufgeraeumt sein");
        }
        assertFalse(Files.exists(pluginsDir.resolve("update")));
    }

    @Test
    void networkFailureLeavesEverythingUntouched() throws Exception {
        DownloadPipeline pl = new DownloadPipeline(tmpDir, (u, t) -> { Files.writeString(t, "partial"); throw new IOException("boom"); }, "1.21.11", false);
        assertThrows(DownloadPipeline.DownloadException.class, () -> pl.prepare(candidate("https://x/f.jar", Map.of(), Map.of())));
        assertUntouched();
    }

    @Test
    void htmlInsteadOfJarIsRejected() throws Exception {
        DownloadPipeline pl = new DownloadPipeline(tmpDir, (u, t) -> Files.writeString(t, "<html>captcha</html>" + "x".repeat(64)), "1.21.11", false);
        assertThrows(DownloadPipeline.DownloadException.class, () -> pl.prepare(candidate("https://x/f.jar", Map.of(), Map.of())));
        assertUntouched();
    }

    @Test
    void jarOfOtherPluginIsRejected() throws Exception {
        DownloadPipeline pl = new DownloadPipeline(tmpDir, (u, t) -> TestJars.plugin(t, "Other", "2.0", "n"), "1.21.11", false);
        assertThrows(DownloadPipeline.DownloadException.class, () -> pl.prepare(candidate("https://x/f.jar", Map.of(), Map.of())));
        assertUntouched();
    }

    @Test
    void checksumMismatchIsRejected() throws Exception {
        DownloadPipeline pl = new DownloadPipeline(tmpDir, (u, t) -> TestJars.plugin(t, "Foo", "2.0", "n"), "1.21.11", false);
        var c = candidate("https://x/f.jar", Map.of("SHA-256", "0".repeat(64)), Map.of());
        assertThrows(DownloadPipeline.DownloadException.class, () -> pl.prepare(c));
        assertUntouched();
        var manual = candidate("https://x/f.jar", Map.of(), Map.of("sha256", "f".repeat(64)));
        assertThrows(DownloadPipeline.DownloadException.class, () -> pl.prepare(manual));
        assertUntouched();
    }

    @Test
    void missingDownloadUrlIsReported() {
        DownloadPipeline pl = new DownloadPipeline(tmpDir, (u, t) -> fail("must not be called"), "1.21.11", false);
        assertThrows(DownloadPipeline.DownloadException.class, () -> pl.prepare(candidate(null, Map.of(), Map.of())));
    }

    @Test
    void requireChecksumRejectsUnverifiableDownloads() throws Exception {
        DownloadPipeline pl = new DownloadPipeline(tmpDir, (u, t) -> TestJars.plugin(t, "Foo", "2.0", "n"), "1.21.11", true);
        assertThrows(DownloadPipeline.DownloadException.class, () -> pl.prepare(candidate("https://x/f.jar", Map.of(), Map.of())));
        assertUntouched();
    }

    @Test
    void validDownloadWithMatchingChecksumIsPrepared() throws Exception {
        Path ref = TestJars.plugin(dir.resolve("ref.jar"), "Foo", "2.0", "new");
        String sha = Hashing.sha256(ref);
        DownloadPipeline pl = new DownloadPipeline(tmpDir, (u, t) -> Files.copy(ref, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING), "1.21.11", true);
        DownloadPipeline.Prepared p = pl.prepare(candidate("https://x/f.jar", Map.of("SHA-256", sha), Map.of()));
        assertEquals(sha, p.sha256());
        assertTrue(Files.isRegularFile(p.tmp()));
        assertArrayEquals(installedBytes, Files.readAllBytes(installed));
    }
}
