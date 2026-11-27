package de.scamupdates.update;

import de.scamupdates.TestJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class JarValidatorTest {
    @TempDir Path dir;

    @Test
    void acceptsMatchingPlugin() throws Exception {
        Path jar = TestJars.plugin(dir.resolve("a.jar"), "Foo", "1.0", "x");
        JarValidator.Info info = JarValidator.validate(jar, "foo", "1.21.11");
        assertEquals("Foo", info.name());
        assertEquals("1.0", info.version());
    }

    @Test
    void rejectsOtherPlugin() throws Exception {
        Path jar = TestJars.plugin(dir.resolve("a.jar"), "Bar", "1.0", "x");
        assertThrows(JarValidator.ValidationException.class, () -> JarValidator.validate(jar, "Foo", "1.21.11"));
    }

    @Test
    void rejectsHtmlAndGarbage() throws Exception {
        Path html = dir.resolve("err.jar");
        Files.writeString(html, "<html>403 Forbidden</html>" + "x".repeat(100));
        assertThrows(JarValidator.ValidationException.class, () -> JarValidator.validate(html, "Foo", "1.21.11"));
        Path empty = dir.resolve("empty.jar");
        Files.write(empty, new byte[0]);
        assertThrows(JarValidator.ValidationException.class, () -> JarValidator.validate(empty, "Foo", "1.21.11"));
    }

    @Test
    void rejectsJarWithoutPluginYmlOrMainClass() throws Exception {
        Path noYml = dir.resolve("noyml.jar");
        try (var os = Files.newOutputStream(noYml); var zip = new ZipOutputStream(os)) {
            TestJars.put(zip, "readme.txt", "hi".getBytes(StandardCharsets.UTF_8));
        }
        assertThrows(JarValidator.ValidationException.class, () -> JarValidator.validate(noYml, "Foo", "1.21.11"));

        Path noMain = dir.resolve("nomain.jar");
        try (var os = Files.newOutputStream(noMain); var zip = new ZipOutputStream(os)) {
            TestJars.put(zip, "plugin.yml", "name: Foo\nmain: com.nope.Main\nversion: 1\n".getBytes(StandardCharsets.UTF_8));
        }
        assertThrows(JarValidator.ValidationException.class, () -> JarValidator.validate(noMain, "Foo", "1.21.11"));
    }

    @Test
    void rejectsZipSlipEntries() throws Exception {
        Path jar = dir.resolve("slip.jar");
        try (var os = Files.newOutputStream(jar); var zip = new ZipOutputStream(os)) {
            TestJars.put(zip, "plugin.yml", "name: Foo\nmain: com.test.Foo\nversion: 1\n".getBytes(StandardCharsets.UTF_8));
            TestJars.put(zip, "com/test/Foo.class", new byte[]{1});
            TestJars.put(zip, "../../evil.txt", new byte[]{1});
        }
        assertThrows(JarValidator.ValidationException.class, () -> JarValidator.validate(jar, "Foo", "1.21.11"));
    }

    @Test
    void rejectsApiVersionNewerThanServer() throws Exception {
        Path jar = TestJars.plugin(dir.resolve("new.jar"), "Foo", "2.0", "1.22", "x");
        assertThrows(JarValidator.ValidationException.class, () -> JarValidator.validate(jar, "Foo", "1.21.11"));
        Path ok = TestJars.plugin(dir.resolve("ok.jar"), "Foo", "2.0", "1.21", "x");
        assertDoesNotThrow(() -> JarValidator.validate(ok, "Foo", "1.21.11"));
        Path legacy = TestJars.plugin(dir.resolve("legacy.jar"), "Foo", "2.0", null, "x");
        assertDoesNotThrow(() -> JarValidator.validate(legacy, "Foo", "1.21.11"));
    }
}
