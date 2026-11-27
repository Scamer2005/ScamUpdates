package de.scamupdates;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TestJars {
    private TestJars() {}

    public static Path plugin(Path file, String name, String version, String payload) throws IOException {
        return plugin(file, name, version, "1.21", payload);
    }

    public static Path plugin(Path file, String name, String version, String apiVersion, String payload) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        String yml = "name: " + name + "\nversion: '" + version + "'\nmain: com.test." + name + "\n"
                + (apiVersion == null ? "" : "api-version: '" + apiVersion + "'\n");
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            put(zip, "plugin.yml", yml.getBytes(StandardCharsets.UTF_8));
            put(zip, "com/test/" + name + ".class", new byte[]{(byte) 0xCA, (byte) 0xFE});
            put(zip, "payload.txt", payload.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    public static void put(ZipOutputStream zip, String entry, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(data);
        zip.closeEntry();
    }
}
