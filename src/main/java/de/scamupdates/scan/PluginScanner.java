package de.scamupdates.scan;

import de.scamupdates.model.PluginInfo;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

public final class PluginScanner {
    private PluginScanner() {}

    private static volatile MetaReader reader;

    private static MetaReader reader() {
        MetaReader r = reader;
        if (r != null) return r;
        try {
            Class.forName("io.papermc.paper.plugin.configuration.PluginMeta");
            Plugin.class.getMethod("getPluginMeta");
            r = new ModernMetaReader();
        } catch (ReflectiveOperationException | LinkageError e) {
            r = new LegacyMetaReader();
        }
        reader = r;
        return r;
    }

    /** Nur auf dem Hauptthread aufrufen (Bukkit-API). Dateizugriffe passieren spaeter asynchron. */
    public static List<PluginInfo> collectMetadata() {
        MetaReader rd = reader();
        List<PluginInfo> list = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            MetaReader.Basics b = rd.read(plugin);
            list.add(new PluginInfo(b.name(), b.version(), b.authors(), b.website(), b.description(), codeSource(plugin), null));
        }
        return list;
    }

    /** Liest die installierte Version eines Plugins versionssicher ueber Modern/LegacyMetaReader. */
    public static java.util.Optional<String> installedVersion(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return java.util.Optional.empty();
        Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
        if (p == null) return java.util.Optional.empty();
        String v = reader().read(p).version();
        return v == null || v.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(v);
    }

    private static Path codeSource(Plugin plugin) {
        try {
            CodeSource cs = plugin.getClass().getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            return Path.of(cs.getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
