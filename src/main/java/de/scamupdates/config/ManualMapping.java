package de.scamupdates.config;

import de.scamupdates.model.Channel;
import de.scamupdates.model.ProjectRef;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public record ManualMapping(String pluginName, boolean enabled, String source, String projectId,
                            String downloadUrl, String sha256, Channel channel, Map<String, String> extra) {

    /** Eine Zuordnung ersetzt die Suche nur, wenn source oder download-url angegeben ist. */
    public Optional<ProjectRef> toProjectRef() {
        String src = source == null ? "" : source;
        boolean hasUrl = downloadUrl != null && !downloadUrl.isBlank();
        if (src.isBlank() && !hasUrl) return Optional.empty();
        Map<String, String> ex = new HashMap<>(extra);
        if (src.isBlank()) src = "direct";
        String id = projectId != null && !projectId.isBlank() ? projectId : (hasUrl ? downloadUrl : "");
        if (id.isBlank()) return Optional.empty();
        return Optional.of(new ProjectRef(src, id, pluginName + " (manuell)", hasUrl ? downloadUrl : id, ex));
    }
}
