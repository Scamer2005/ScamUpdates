package de.scamupdates.scan;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.util.List;

/** Klassischer Zugriff ueber PluginDescriptionFile (aeltere Versionen). */
@SuppressWarnings("deprecation")
final class LegacyMetaReader implements MetaReader {
    @Override
    public Basics read(Plugin plugin) {
        PluginDescriptionFile d = plugin.getDescription();
        return new Basics(d.getName(), d.getVersion(), List.copyOf(d.getAuthors()), d.getWebsite(), d.getDescription());
    }
}
