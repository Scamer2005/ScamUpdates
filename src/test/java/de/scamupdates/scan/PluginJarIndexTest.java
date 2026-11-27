package de.scamupdates.scan;

import de.scamupdates.TestJars;
import de.scamupdates.model.PluginInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginJarIndexTest {
    @TempDir Path dir;

    @Test
    void indexesByPluginNameIgnoringFileNamesAndJunk() throws Exception {
        TestJars.plugin(dir.resolve("WorldEdit-7.3.jar"), "WorldEdit", "7.3", "a");
        TestJars.plugin(dir.resolve("b.jar"), "Vault", "1.7", "b");
        Files.writeString(dir.resolve("kaputt.jar"), "kein zip");
        Files.writeString(dir.resolve("notes.txt"), "x");
        Map<String, Path> idx = PluginJarIndex.build(dir);
        assertEquals(2, idx.size());
        assertEquals("WorldEdit-7.3.jar", idx.get("worldedit").getFileName().toString());
        assertEquals("b.jar", idx.get("vault").getFileName().toString());
    }

    @Test
    void attachPrefersIndexOverRemappedCodeSource() throws Exception {
        Path real = TestJars.plugin(dir.resolve("Foo.jar"), "Foo", "1.0", "a");
        Path remapped = dir.resolve(".paper-remapped/Foo.jar");
        PluginInfo meta = new PluginInfo("Foo", "1.0", List.of(), null, null, remapped, null);
        PluginInfo missing = new PluginInfo("Ghost", "1.0", List.of(), null, null, null, null);
        List<PluginInfo> out = PluginJarIndex.attach(List.of(meta, missing), dir);
        assertEquals(real, out.get(0).jarPath());
        assertNull(out.get(1).jarPath());
    }
}
