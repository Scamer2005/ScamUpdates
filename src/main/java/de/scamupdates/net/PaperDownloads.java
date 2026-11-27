package de.scamupdates.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.scamupdates.compat.ServerVersion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PaperMC Downloads-Dienst "Fill" v3 (https://docs.papermc.io/misc/downloads-api):
 *   GET /v3/projects/paper                       -> Versionen
 *   GET /v3/projects/paper/versions/{v}/builds   -> Builds (id, channel, downloads."server:default".{name,url,checksums.sha256,size})
 * Es werden nur STABLE-Builds angeboten.
 */
public final class PaperDownloads {
    private static final String API = "https://fill.papermc.io/v3/projects/paper";
    private static final String DOWNLOAD_KEY = "server:default";

    public record Build(int id, String channel, String fileName, String url, String sha256, long size) {}

    /** Ergebnis der Paper-Pruefung fuer die Statusanzeige. */
    public record PaperStatus(String minecraft, int currentBuild, int latestBuild, String newestMinecraft,
                              boolean newerBuildAvailable, boolean newerMinecraftAvailable, String error) {
        public boolean failed() { return error != null; }
    }

    private final HttpService http;

    public PaperDownloads(HttpService http) { this.http = http; }

    public List<String> versions() throws IOException, InterruptedException {
        return parseVersions(http.getJson(API, Map.of()));
    }

    public List<Build> builds(String version) throws IOException, InterruptedException {
        return parseBuilds(http.getJson(API + "/versions/" + version.replaceAll("[^0-9A-Za-z._-]", "") + "/builds", Map.of()));
    }

    public Optional<Build> latestStable(String version) throws IOException, InterruptedException {
        return builds(version).stream().filter(b -> b.channel().equalsIgnoreCase("STABLE")).findFirst();
    }

    public PaperStatus status(String mc, int currentBuild) {
        try {
            Optional<Build> latest = latestStable(mc);
            List<String> versions = versions();
            String newest = versions.isEmpty() ? mc : versions.get(0);
            int latestId = latest.map(Build::id).orElse(-1);
            boolean newerBuild = latestId > 0 && currentBuild > 0 && latestId > currentBuild;
            boolean newerMc = ServerVersion.parse(newest).flatMap(n -> ServerVersion.parse(mc).map(c -> n.compareTo(c) > 0)).orElse(false);
            return new PaperStatus(mc, currentBuild, latestId, newest, newerBuild, newerMc, null);
        } catch (IOException e) {
            return new PaperStatus(mc, currentBuild, -1, mc, false, false, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PaperStatus(mc, currentBuild, -1, mc, false, false, "unterbrochen");
        }
    }

    // --- Parsing (paketsichtbar fuer Tests) ---

    static List<String> parseVersions(JsonElement root) throws IOException {
        List<String> out = new ArrayList<>();
        if (!root.isJsonObject()) throw new IOException("Unerwartete Antwort der Paper-API");
        JsonElement v = root.getAsJsonObject().get("versions");
        if (v == null) throw new IOException("Paper-API: 'versions' fehlt");
        if (v.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) addAll(out, e.getValue());
        } else {
            addAll(out, v);
        }
        out.sort(Comparator.comparing((String s) -> ServerVersion.parse(s).orElse(null),
                Comparator.nullsFirst(Comparator.<ServerVersion>naturalOrder())).reversed());
        return out;
    }

    private static void addAll(List<String> out, JsonElement arr) {
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement e : arr.getAsJsonArray()) if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
    }

    static List<Build> parseBuilds(JsonElement root) throws IOException {
        if (!root.isJsonArray()) throw new IOException("Unerwartete Antwort der Paper-API (Builds)");
        List<Build> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject b = e.getAsJsonObject();
            JsonObject downloads = b.has("downloads") && b.get("downloads").isJsonObject() ? b.getAsJsonObject("downloads") : null;
            JsonObject d = downloads != null && downloads.has(DOWNLOAD_KEY) && downloads.get(DOWNLOAD_KEY).isJsonObject()
                    ? downloads.getAsJsonObject(DOWNLOAD_KEY) : null;
            if (d == null || !b.has("id")) continue;
            String sha = "";
            if (d.has("checksums") && d.get("checksums").isJsonObject() && d.getAsJsonObject("checksums").has("sha256")) {
                sha = d.getAsJsonObject("checksums").get("sha256").getAsString();
            }
            out.add(new Build(b.get("id").getAsInt(), b.has("channel") ? b.get("channel").getAsString() : "",
                    d.has("name") ? d.get("name").getAsString() : "", d.has("url") ? d.get("url").getAsString() : "",
                    sha, d.has("size") ? d.get("size").getAsLong() : -1));
        }
        out.sort(Comparator.comparingInt(Build::id).reversed());
        return out;
    }
}
