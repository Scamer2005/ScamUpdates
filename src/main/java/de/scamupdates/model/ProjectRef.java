package de.scamupdates.model;

import java.util.Map;

public record ProjectRef(String sourceId, String projectId, String name, String url, Map<String, String> extra) {
    public ProjectRef(String sourceId, String projectId, String name, String url) {
        this(sourceId, projectId, name, url, Map.of());
    }

    public String key() {
        return sourceId + ":" + projectId;
    }
}
