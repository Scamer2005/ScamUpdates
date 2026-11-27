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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jenkins-Jobs, nur per manueller Zuordnung (projectId = Job-URL, optional extra "artifact-regex").
 * Es wird der letzte erfolgreiche Build abgefragt; die Version wird aus dem Dateinamen gelesen (Kanal: DEVELOPMENT).
 */
public final class JenkinsSource implements UpdateSource {
    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+[^/]*?)(?:\\.jar)?$");
    private final HttpService http;

    public JenkinsSource(HttpService http) { this.http = http; }

    @Override public String id() { return "jenkins"; }
    @Override public String displayName() { return "Jenkins"; }
    @Override public boolean supportsSearch() { return false; }
    @Override public List<ProjectRef> search(PluginInfo plugin) { return List.of(); }

    @Override
    public Optional<RemoteRelease> latest(ProjectRef project, ReleaseQuery q) throws IOException, InterruptedException {
        if (!q.maxChannel().allows(Channel.DEVELOPMENT)) return Optional.empty();
        String job = project.projectId().replaceAll("/+$", "");
        String api = job + "/lastSuccessfulBuild/api/json?tree=number,artifacts[fileName,relativePath]";
        JsonElement root = http.getJson(api, Map.of());
        if (!root.isJsonObject()) return Optional.empty();
        JsonObject build = root.getAsJsonObject();
        String regex = project.extra().getOrDefault("artifact-regex", ".*\\.jar");
        Pattern pat = Pattern.compile(regex);
        List<JsonObject> matches = new ArrayList<>();
        for (JsonElement e : Json.arr(build, "artifacts")) {
            if (!e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject();
            String fn = Json.str(a, "fileName");
            if (fn == null || !pat.matcher(fn).matches()) continue;
            String l = fn.toLowerCase();
            if (l.endsWith("-sources.jar") || l.endsWith("-javadoc.jar")) continue;
            matches.add(a);
        }
        if (matches.size() != 1) {
            throw new IOException("Jenkins: " + matches.size() + " passende Artefakte (genau 1 erwartet) - 'artifact-regex' praezisieren");
        }
        JsonObject art = matches.get(0);
        String fileName = Json.str(art, "fileName");
        String rel = Json.str(art, "relativePath");
        StringBuilder path = new StringBuilder();
        for (String seg : (rel == null ? fileName : rel).split("/")) {
            if (seg.isEmpty() || seg.equals("..")) continue;
            if (path.length() > 0) path.append('/');
            path.append(Json.encPath(seg));
        }
        Matcher m = VERSION.matcher(fileName);
        String version = m.find() ? m.group(1) : "build-" + Json.lng(build, "number", 0);
        return Optional.of(new RemoteRelease(version, Channel.DEVELOPMENT, job + "/lastSuccessfulBuild/artifact/" + path,
                fileName, Map.of(), -1, List.of(), false));
    }
}
