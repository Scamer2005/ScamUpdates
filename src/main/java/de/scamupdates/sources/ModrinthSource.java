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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Modrinth API v2 (https://docs.modrinth.com/api/). */
public final class ModrinthSource implements UpdateSource {
    private static final String API = "https://api.modrinth.com/v2";
    private static final String LOADERS = "[\"paper\",\"spigot\",\"bukkit\",\"purpur\",\"folia\"]";
    private static final String FACETS =
            "[[\"categories:paper\",\"categories:spigot\",\"categories:bukkit\",\"categories:purpur\",\"categories:folia\"]]";

    private final HttpService http;

    public ModrinthSource(HttpService http) { this.http = http; }

    @Override public String id() { return "modrinth"; }
    @Override public String displayName() { return "Modrinth"; }
    @Override public boolean supportsSearch() { return true; }

    @Override
    public List<ProjectRef> search(PluginInfo plugin) throws IOException, InterruptedException {
        String url = API + "/search?query=" + Json.enc(plugin.name()) + "&limit=10&facets=" + Json.enc(FACETS);
        JsonElement root = http.getJson(url, Map.of());
        List<ProjectRef> out = new ArrayList<>();
        if (!root.isJsonObject()) return out;
        for (JsonElement e : Json.arr(root.getAsJsonObject(), "hits")) {
            if (!e.isJsonObject()) continue;
            JsonObject h = e.getAsJsonObject();
            String id = Json.str(h, "project_id"), title = Json.str(h, "title"), slug = Json.str(h, "slug");
            if (id == null || title == null) continue;
            out.add(new ProjectRef(id(), id, title, "https://modrinth.com/project/" + (slug != null ? slug : id)));
        }
        return out;
    }

    @Override
    public Optional<RemoteRelease> latest(ProjectRef project, ReleaseQuery q) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(API + "/project/" + Json.encPath(project.projectId()) + "/version?loaders=" + Json.enc(LOADERS));
        if (q.requireMcMatch() && q.minecraftVersion() != null) {
            url.append("&game_versions=").append(Json.enc("[\"" + q.minecraftVersion() + "\"]"));
        }
        JsonElement root = http.getJson(url.toString(), Map.of());
        if (!root.isJsonArray()) return Optional.empty();
        for (JsonElement e : root.getAsJsonArray()) { // neueste zuerst
            if (!e.isJsonObject()) continue;
            JsonObject v = e.getAsJsonObject();
            Channel ch = switch (String.valueOf(Json.str(v, "version_type"))) {
                case "beta" -> Channel.BETA;
                case "alpha" -> Channel.DEVELOPMENT;
                default -> Channel.STABLE;
            };
            if (!q.maxChannel().allows(ch)) continue;
            JsonObject file = pickFile(v);
            if (file == null) continue;
            Map<String, String> hashes = new LinkedHashMap<>();
            JsonObject h = Json.obj(file, "hashes");
            if (h != null) {
                if (Json.str(h, "sha512") != null) hashes.put("SHA-512", Json.str(h, "sha512"));
                if (Json.str(h, "sha1") != null) hashes.put("SHA-1", Json.str(h, "sha1"));
            }
            String version = Json.str(v, "version_number");
            if (version == null || Json.str(file, "url") == null) continue;
            return Optional.of(new RemoteRelease(version, ch, Json.str(file, "url"), Json.str(file, "filename"), hashes,
                    Json.lng(file, "size", -1), Json.strings(Json.arr(v, "game_versions")), false));
        }
        return Optional.empty();
    }

    private static JsonObject pickFile(JsonObject version) {
        JsonObject first = null;
        for (JsonElement fe : Json.arr(version, "files")) {
            if (!fe.isJsonObject()) continue;
            JsonObject f = fe.getAsJsonObject();
            String name = Json.str(f, "filename");
            if (name == null || !name.toLowerCase().endsWith(".jar")) continue;
            if (Json.bool(f, "primary")) return f;
            if (first == null) first = f;
        }
        return first;
    }
}
