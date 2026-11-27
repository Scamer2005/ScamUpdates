package de.scamupdates.scan;

import de.scamupdates.model.PluginInfo;
import de.scamupdates.update.JarValidator;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ordnet laufende Plugins den echten JAR-Dateien im plugins-Ordner zu (liest nur die plugin.yml). */
public final class PluginJarIndex {
    private PluginJarIndex() {}

    public static Map<String, Path> build(Path pluginsDir) throws IOException {
        Map<String, Path> map = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    map.putIfAbsent(JarValidator.readMeta(p).name().toLowerCase(Locale.ROOT), p);
                } catch (JarValidator.ValidationException ignored) {
                    // Fremde/defekte Dateien werden ignoriert und nie angefasst.
                }
            }
        }
        return map;
    }

    /** Bevorzugt den Index (robust gegen Paper-Remapping), faellt sonst auf den CodeSource-Pfad zurueck. */
    public static List<PluginInfo> attach(List<PluginInfo> plugins, Path pluginsDir) throws IOException {
        Map<String, Path> index = build(pluginsDir);
        Path root = pluginsDir.toAbsolutePath().normalize();
        List<PluginInfo> out = new ArrayList<>();
        for (PluginInfo p : plugins) {
            Path jar = index.get(p.name().toLowerCase(Locale.ROOT));
            if (jar == null && p.codeSource() != null) {
                Path cs = p.codeSource().toAbsolutePath().normalize();
                if (cs.getParent() != null && cs.getParent().equals(root) && Files.isRegularFile(cs)) jar = cs;
            }
            out.add(p.withJar(jar));
        }
        return out;
    }
}
