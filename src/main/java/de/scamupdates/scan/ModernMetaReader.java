package de.scamupdates.scan;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;

/** Paper-PluginMeta (neuere Paper-Versionen). Wird nur geladen, wenn die Klasse existiert. */
final class ModernMetaReader implements MetaReader {
    @Override
    public Basics read(Plugin plugin) {
        PluginMeta m = plugin.getPluginMeta();
        return new Basics(m.getName(), m.getVersion(), List.copyOf(m.getAuthors()), m.getWebsite(), m.getDescription());
    }
}
