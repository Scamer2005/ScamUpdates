package de.scamupdates.scan;

import org.bukkit.plugin.Plugin;

import java.util.List;

/** Versionsabhaengiger Zugriff auf die Plugin-Metadaten; es wird nur eine Implementierung geladen. */
interface MetaReader {
    record Basics(String name, String version, List<String> authors, String website, String description) {}

    Basics read(Plugin plugin);
}
