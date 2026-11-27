package de.scamupdates.model;

import java.nio.file.Path;
import java.util.List;

public record PluginInfo(String name, String version, List<String> authors, String website,
                         String description, Path codeSource, Path jarPath) {
    public PluginInfo withJar(Path jar) {
        return new PluginInfo(name, version, authors, website, description, codeSource, jar);
    }
}
