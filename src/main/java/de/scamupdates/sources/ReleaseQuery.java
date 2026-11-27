package de.scamupdates.sources;

import de.scamupdates.model.Channel;
import de.scamupdates.model.PluginInfo;

public record ReleaseQuery(Channel maxChannel, String minecraftVersion, boolean requireMcMatch, PluginInfo plugin) {}
