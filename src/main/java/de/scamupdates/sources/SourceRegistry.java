package de.scamupdates.sources;

import de.scamupdates.config.ConfigManager;
import de.scamupdates.net.HttpService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class SourceRegistry {
    private final Map<String, UpdateSource> sources = new LinkedHashMap<>();
    private final ConfigManager config;

    public SourceRegistry(HttpService http, ConfigManager config) {
        this.config = config;
        register(new ModrinthSource(http));
        register(new HangarSource(http));
        register(new SpigotSource(http));
        register(new GithubSource(http, config::githubToken));
        register(new JenkinsSource(http));
        register(new DirectSource());
    }

    private void register(UpdateSource s) { sources.put(s.id(), s); }

    public Optional<UpdateSource> get(String id) { return Optional.ofNullable(sources.get(id)); }

    public boolean enabled(String id) { return config.sourceEnabled(id); }

    public Iterable<UpdateSource> all() { return sources.values(); }
}
