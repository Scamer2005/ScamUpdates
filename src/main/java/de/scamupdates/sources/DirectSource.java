package de.scamupdates.sources;

import de.scamupdates.model.Channel;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.model.RemoteRelease;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Direkter Download-Link (nur manuelle Zuordnung). Ein Update wird erkannt, wenn
 *  - "sha256" konfiguriert ist und vom SHA-256 der installierten Datei abweicht, oder
 *  - "version" konfiguriert ist und neuer als die installierte Version ist.
 * Ohne beides kann ohne Download nicht entschieden werden -> Fehlermeldung.
 */
public final class DirectSource implements UpdateSource {
    @Override public String id() { return "direct"; }
    @Override public String displayName() { return "Direkter Link"; }
    @Override public boolean supportsSearch() { return false; }
    @Override public List<ProjectRef> search(PluginInfo plugin) { return List.of(); }

    @Override
    public Optional<RemoteRelease> latest(ProjectRef project, ReleaseQuery q) throws IOException {
        Map<String, String> ex = project.extra();
        String url = ex.getOrDefault("download-url", project.projectId());
        String sha = ex.get("sha256");
        String version = ex.get("version");
        if ((sha == null || sha.isBlank()) && (version == null || version.isBlank())) {
            throw new IOException("direct: 'sha256' oder 'version' muss in der Config angegeben werden, damit Updates erkannt werden koennen");
        }
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        boolean hashDecides = sha != null && !sha.isBlank() && (version == null || version.isBlank());
        Map<String, String> hashes = sha == null || sha.isBlank() ? Map.of() : Map.of("SHA-256", sha.toLowerCase());
        String shown = version != null && !version.isBlank() ? version : "sha256:" + sha.substring(0, Math.min(8, sha.length()));
        return Optional.of(new RemoteRelease(shown, Channel.STABLE, url, fileName, hashes, -1, List.of(), hashDecides));
    }
}
