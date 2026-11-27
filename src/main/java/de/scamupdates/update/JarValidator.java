package de.scamupdates.update;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validiert JARs, ohne sie auszufuehren oder zu entpacken: nur plugin.yml/paper-plugin.yml wird gelesen. */
public final class JarValidator {
    private JarValidator() {}

    public static final class ValidationException extends Exception {
        public ValidationException(String message) { super(message); }
    }

    public record Info(String name, String version, String main, String apiVersion) {}

    private static final int MAX_ENTRIES = 50_000;
    private static final int MAX_YAML_BYTES = 1024 * 1024;

    public static Info readMeta(Path jar) throws ValidationException {
        try {
            if (!Files.isRegularFile(jar) || Files.size(jar) < 22) throw new ValidationException("Datei fehlt oder ist leer");
            try (InputStream in = Files.newInputStream(jar)) {
                byte[] magic = in.readNBytes(4);
                if (magic.length < 4 || magic[0] != 'P' || magic[1] != 'K' || magic[2] != 3 || magic[3] != 4) {
                    throw new ValidationException("Keine gueltige JAR/ZIP-Datei (z. B. HTML-Fehlerseite statt Download)");
                }
            }
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                int count = 0;
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (++count > MAX_ENTRIES) throw new ValidationException("JAR enthaelt unplausibel viele Eintraege");
                    String n = e.getName();
                    if (n.startsWith("/") || n.startsWith("\\") || n.contains("../") || n.contains("..\\") || n.equals("..")) {
                        throw new ValidationException("JAR enthaelt unsichere Pfade (Zip-Slip)");
                    }
                }
                ZipEntry meta = zip.getEntry("plugin.yml");
                if (meta == null) meta = zip.getEntry("paper-plugin.yml");
                if (meta == null) throw new ValidationException("Weder plugin.yml noch paper-plugin.yml gefunden");
                byte[] data;
                try (InputStream in = zip.getInputStream(meta)) {
                    data = in.readNBytes(MAX_YAML_BYTES + 1);
                }
                if (data.length > MAX_YAML_BYTES) throw new ValidationException("plugin.yml ist unplausibel gross");
                YamlConfiguration y = new YamlConfiguration();
                try {
                    y.loadFromString(new String(data, StandardCharsets.UTF_8));
                } catch (InvalidConfigurationException ex) {
                    throw new ValidationException("plugin.yml ist kein gueltiges YAML");
                }
                String name = y.getString("name");
                String main = y.getString("main");
                if (name == null || name.isBlank()) throw new ValidationException("plugin.yml enthaelt keinen Namen");
                if (main == null || main.isBlank()) throw new ValidationException("plugin.yml enthaelt keine Hauptklasse");
                if (zip.getEntry(main.replace('.', '/') + ".class") == null) {
                    throw new ValidationException("Hauptklasse " + main + " fehlt in der JAR");
                }
                return new Info(name.trim(), y.getString("version"), main.trim(), y.getString("api-version"));
            }
        } catch (IOException e) {
            throw new ValidationException("JAR nicht lesbar/beschaedigt: " + e.getMessage());
        }
    }

    /** Wie readMeta, zusaetzlich: Pluginname muss passen und api-version darf nicht neuer als der Server sein. */
    public static Info validate(Path jar, String expectedName, String serverMcVersion) throws ValidationException {
        Info info = readMeta(jar);
        if (!info.name().equalsIgnoreCase(expectedName)) {
            throw new ValidationException("Die Datei gehoert zu einem anderen Plugin ('" + info.name() + "' statt '" + expectedName + "')");
        }
        if (info.apiVersion() != null && serverMcVersion != null && apiNewerThanServer(info.apiVersion(), serverMcVersion)) {
            throw new ValidationException("Plugin verlangt api-version " + info.apiVersion() + ", Server ist " + serverMcVersion);
        }
        return info;
    }

    static boolean apiNewerThanServer(String api, String server) {
        int[] a = majorMinor(api), s = majorMinor(server);
        if (a == null || s == null) return false;
        return a[0] > s[0] || (a[0] == s[0] && a[1] > s[1]);
    }

    private static int[] majorMinor(String v) {
        String[] p = v.trim().split("\\.");
        if (p.length < 2) return null;
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1].replaceAll("\\D.*$", ""))};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
