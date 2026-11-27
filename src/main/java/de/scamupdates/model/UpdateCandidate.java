package de.scamupdates.model;

public record UpdateCandidate(PluginInfo plugin, ProjectRef project, RemoteRelease release) {
    public String fromVersion() { return plugin.version(); }
    public String toVersion() { return release.version(); }
}
