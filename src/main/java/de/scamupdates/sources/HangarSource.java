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

/** Hangar API v1 (https://hangar.papermc.io/api-docs). Nur die PAPER-Plattform wird beruecksichtigt. */
public final class HangarSource implements UpdateSource {
    private static final String API = "https://hangar.papermc.io/api/v1";
    private final HttpService http;

    public HangarSource(HttpService http) { this.http = http; }

    @Override public String id() { return "hangar"; }
    @Override public String displayName() { return "Hangar"; }
    @Override public boolean supportsSearch() { return true; }

    @Override
    public List<ProjectRef> search(PluginInfo plugin) throws IOException, InterruptedException {
        JsonElement root = http.getJson(API + "/projects?limit=10&q=" + Json.enc(plugin.name()), Map.of());
        List<ProjectRef> out = new ArrayList<>();
        if (!root.isJsonObject()) return out;
        for (JsonElement e : Json.arr(root.getAsJsonObject(), "result")) {
            if (!e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            JsonObject ns = Json.obj(p, "namespace");
            String name = Json.str(p, "name"), owner = Json.str(ns, "owner"), slug = Json.str(ns, "slug");
            if (name == null || slug == null) continue;
            out.add(new ProjectRef(id(), slug, name, "https://hangar.papermc.io/" + (owner != null ? owner + "/" : "") + slug));
        }
        return out;
    }

    @Override
    public Optional<RemoteRelease> latest(ProjectRef project, ReleaseQuery q) throws IOException, InterruptedException {
        JsonElement root = http.getJson(API + "/projects/" + Json.encPath(project.projectId()) + "/versions?limit=25&platform=PAPER", Map.of());
        if (!root.isJsonObject()) return Optional.empty();
        for (JsonElement e : Json.arr(root.getAsJsonObject(), "result")) {
            if (!e.isJsonObject()) continue;
            JsonObject v = e.getAsJsonObject();
            Channel ch = Channel.fromName(channelName(v.get("channel")));
            if (!q.maxChannel().allows(ch)) continue;
            JsonObject downloads = Json.obj(v, "downloads");
            JsonObject paper = Json.obj(downloads, "PAPER");
            if (paper == null) continue;
            List<String> mcVersions = Json.strings(Json.arr(Json.obj(v, "platformDependencies"), "PAPER"));
            if (q.requireMcMatch() && !mcVersions.isEmpty() && q.minecraftVersion() != null
                    && !McVersionMatcher.anyMatches(mcVersions, q.minecraftVersion())) continue;
            String url = Json.str(paper, "downloadUrl");
            if (url == null) url = Json.str(paper, "externalUrl");
            JsonObject info = Json.obj(paper, "fileInfo");
            Map<String, String> hashes = new java.util.LinkedHashMap<>();
            if (info != null && Json.str(info, "sha256Hash") != null) hashes.put("SHA-256", Json.str(info, "sha256Hash"));
            String version = Json.str(v, "name");
            if (version == null) continue;
            return Optional.of(new RemoteRelease(version, ch, url, info == null ? null : Json.str(info, "name"), hashes,
                    info == null ? -1 : Json.lng(info, "sizeBytes", -1), mcVersions, false));
        }
        return Optional.empty();
    }

    private static String channelName(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonObject()) {
            String n = Json.str(el.getAsJsonObject(), "name");
            return n == null ? "" : n;
        }
        return "";
    }
}
