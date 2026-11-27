package de.scamupdates.sources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.scamupdates.model.Channel;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.model.RemoteRelease;
import de.scamupdates.net.HttpService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SpigotMC ueber die Community-API Spiget (https://spiget.org). SpigotMC selbst bietet keine offizielle API fuer
 * Downloads an. Kostenpflichtige und extern gehostete Ressourcen liefern nur Versionsinfo ohne Direktdownload.
 */
public final class SpigotSource implements UpdateSource {
    private static final String API = "https://api.spiget.org/v2";
    private final HttpService http;

    public SpigotSource(HttpService http) { this.http = http; }

    @Override public String id() { return "spigot"; }
    @Override public String displayName() { return "SpigotMC (Spiget)"; }
    @Override public boolean supportsSearch() { return true; }

    @Override
    public List<ProjectRef> search(PluginInfo plugin) throws IOException, InterruptedException {
        JsonElement root = http.getJson(API + "/search/resources/" + Json.encPath(plugin.name()) + "?field=name&size=10", Map.of());
        List<ProjectRef> out = new ArrayList<>();
        if (!root.isJsonArray()) return out;
        for (JsonElement e : root.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject r = e.getAsJsonObject();
            String id = Json.str(r, "id"), name = Json.str(r, "name");
            if (id == null || name == null) continue;
            out.add(new ProjectRef(id(), id, name, "https://www.spigotmc.org/resources/" + id));
        }
        return out;
    }

    @Override
    public Optional<RemoteRelease> latest(ProjectRef project, ReleaseQuery q) throws IOException, InterruptedException {
        String rid = Json.encPath(project.projectId());
        JsonElement res = http.getJson(API + "/resources/" + rid, Map.of());
        JsonElement ver = http.getJson(API + "/resources/" + rid + "/versions/latest", Map.of());
        if (!res.isJsonObject() || !ver.isJsonObject()) return Optional.empty();
        JsonObject r = res.getAsJsonObject();
        String version = Json.str(ver.getAsJsonObject(), "name");
        if (version == null) return Optional.empty();
        JsonObject file = Json.obj(r, "file");
        boolean downloadable = !Json.bool(r, "premium") && !Json.bool(r, "external")
                && file != null && ".jar".equalsIgnoreCase(String.valueOf(Json.str(file, "type")));
        String url = downloadable ? API + "/resources/" + rid + "/download" : null;
        return Optional.of(new RemoteRelease(version, Channel.STABLE, url, null, Map.of(), -1, List.of(), false));
    }
}
