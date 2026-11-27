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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** GitHub Releases ueber die REST-API (https://docs.github.com/rest/releases). projectId = "owner/repo". */
public final class GithubSource implements UpdateSource {
    private static final String API = "https://api.github.com";
    private final HttpService http;
    private final Supplier<String> token;

    public GithubSource(HttpService http, Supplier<String> token) {
        this.http = http;
        this.token = token;
    }

    @Override public String id() { return "github"; }
    @Override public String displayName() { return "GitHub Releases"; }

    /** Die Repo-Suche hat ein sehr strenges Limit ohne Token; ohne Token nur Treffer aus Plugin-Metadaten. */
    @Override public boolean supportsSearch() {
        String t = token.get();
        return t != null && !t.isBlank();
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/vnd.github+json");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        String t = token.get();
        if (t != null && !t.isBlank()) h.put("Authorization", "Bearer " + t.trim());
        return h;
    }

    @Override
    public List<ProjectRef> search(PluginInfo plugin) throws IOException, InterruptedException {
        List<ProjectRef> out = new ArrayList<>();
        JsonElement root = http.getJson(API + "/search/repositories?per_page=5&q=" + Json.enc(plugin.name() + " in:name"), headers());
        if (!root.isJsonObject()) return out;
        for (JsonElement e : Json.arr(root.getAsJsonObject(), "items")) {
            if (!e.isJsonObject()) continue;
            JsonObject r = e.getAsJsonObject();
            String full = Json.str(r, "full_name"), name = Json.str(r, "name");
            if (full != null && name != null) out.add(new ProjectRef(id(), full, name, "https://github.com/" + full));
        }
        return out;
    }

    @Override
    public Optional<RemoteRelease> latest(ProjectRef project, ReleaseQuery q) throws IOException, InterruptedException {
        String repo = project.projectId();
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IOException("Ungueltiges GitHub-Repository: " + repo);
        JsonElement root = http.getJson(API + "/repos/" + repo + "/releases?per_page=15", headers());
        if (!root.isJsonArray()) return Optional.empty();
        for (JsonElement e : root.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject rel = e.getAsJsonObject();
            if (Json.bool(rel, "draft")) continue;
            Channel ch = Json.bool(rel, "prerelease") ? Channel.BETA : Channel.STABLE;
            if (!q.maxChannel().allows(ch)) continue;
            JsonObject asset = pickAsset(rel, q.plugin() == null ? "" : q.plugin().name());
            String tag = Json.str(rel, "tag_name");
            if (asset == null || tag == null) continue;
            Map<String, String> hashes = new LinkedHashMap<>();
            String digest = Json.str(asset, "digest"); // "sha256:<hex>", falls von GitHub geliefert
            if (digest != null && digest.toLowerCase().startsWith("sha256:")) hashes.put("SHA-256", digest.substring(7));
            return Optional.of(new RemoteRelease(tag, ch, Json.str(asset, "browser_download_url"), Json.str(asset, "name"),
                    hashes, Json.lng(asset, "size", -1), List.of(), false));
        }
        return Optional.empty();
    }

    /** Genau ein passendes .jar-Asset; bei mehreren zaehlt der Treffer mit dem Pluginnamen, sonst mehrdeutig -> null. */
    private static JsonObject pickAsset(JsonObject release, String pluginName) {
        List<JsonObject> jars = new ArrayList<>();
        for (JsonElement ae : Json.arr(release, "assets")) {
            if (!ae.isJsonObject()) continue;
            JsonObject a = ae.getAsJsonObject();
            String n = String.valueOf(Json.str(a, "name")).toLowerCase();
            if (n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar")) jars.add(a);
        }
        if (jars.size() == 1) return jars.get(0);
        List<JsonObject> named = new ArrayList<>();
        for (JsonObject a : jars) if (NameMatcher.normalize(Json.str(a, "name")).contains(NameMatcher.normalize(pluginName))) named.add(a);
        return named.size() == 1 ? named.get(0) : null;
    }
}
