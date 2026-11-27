package de.scamupdates.crafty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.scamupdates.config.CraftyConfig;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.net.InternetCheck;
import de.scamupdates.net.TlsSupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Locale;

/**
 * Crafty-Controller-4-REST-API (v2), Bearer-Token:
 *   GET  {base}{api}/servers/{id}                      -> Verbindung, Token und Server-ID pruefen
 *   POST {base}{api}/servers/{id}/action/restart_server -> Neustart
 */
public final class CraftyClient {

    public enum Status { OK, AUTH_FAILED, SERVER_NOT_FOUND, UNREACHABLE, TLS_ERROR, REJECTED }

    public record Result(Status status, int httpStatus, String message) {
        public boolean ok() { return status == Status.OK; }
    }

    private final CraftyConfig cfg;
    private final HttpClient client;
    private final ScamLog log;

    public CraftyClient(CraftyConfig cfg, ScamLog log) throws IOException, GeneralSecurityException {
        this.cfg = cfg;
        this.log = log;
        String lower = cfg.baseUrl().trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") && !cfg.allowHttp()) {
            throw new IOException("http:// ist nicht erlaubt (crafty.connection.allow-http)");
        }
        this.client = HttpClient.newBuilder()
                .sslContext(TlsSupport.create(cfg.verifySsl(), cfg.caFile()))
                .followRedirects(HttpClient.Redirect.NEVER) // Token nie an andere Hosts weiterreichen
                .connectTimeout(Duration.ofMillis(cfg.timeoutMs()))
                .build();
    }

    private URI serverUri(String suffix) {
        String base = cfg.baseUrl().trim().replaceAll("/+$", "");
        String api = cfg.apiBasePath().trim().replaceAll("^/+", "").replaceAll("/+$", "");
        return URI.create(base + "/" + api + "/servers/" + cfg.serverId().trim() + suffix);
    }

    public Result verifyConnection() {
        return exchange(serverUri(""), false);
    }

    public Result restartServer() {
        return exchange(serverUri("/action/restart_server"), true);
    }

    private Result exchange(URI uri, boolean post) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(cfg.timeoutMs()))
                .header("Authorization", "Bearer " + cfg.apiToken())
                .header("Accept", "application/json");
        if (post) b.POST(HttpRequest.BodyPublishers.noBody()); else b.GET();
        log.debug("Crafty " + (post ? "POST " : "GET ") + uri.getPath());
        try {
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return interpret(r.statusCode(), r.body(), post);
        } catch (IOException e) {
            InternetCheck.Outcome o = InternetCheck.outcomeOf(e);
            Status st = o == InternetCheck.Outcome.TLS_FAILURE ? Status.TLS_ERROR : Status.UNREACHABLE;
            return new Result(st, -1, log.redact(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Status.UNREACHABLE, -1, "Anfrage unterbrochen");
        }
    }

    private Result interpret(int code, String body, boolean post) {
        if (code == 401 || code == 403) return new Result(Status.AUTH_FAILED, code, "Token ungueltig oder ohne Berechtigung (HTTP " + code + ")");
        if (code == 404) {
            return new Result(post ? Status.REJECTED : Status.SERVER_NOT_FOUND, code, "Server-ID nicht gefunden oder Endpunkt unbekannt (HTTP 404)");
        }
        if (code >= 200 && code < 300) {
            String apiStatus = null, detail = "";
            try {
                JsonElement el = JsonParser.parseString(body == null ? "" : body);
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    if (o.has("status") && o.get("status").isJsonPrimitive()) apiStatus = o.get("status").getAsString();
                    if (o.has("error") && o.get("error").isJsonPrimitive()) detail = o.get("error").getAsString();
                }
            } catch (RuntimeException ignored) {
                // Kein JSON: HTTP-2xx genuegt als Erfolg
            }
            if (apiStatus != null && !apiStatus.equalsIgnoreCase("ok")) {
                return new Result(Status.REJECTED, code, log.redact("Crafty meldet status=" + apiStatus + (detail.isEmpty() ? "" : ": " + shorten(detail))));
            }
            return new Result(Status.OK, code, "OK");
        }
        return new Result(Status.REJECTED, code, "Crafty hat die Anfrage abgelehnt (HTTP " + code + ")");
    }

    private static String shorten(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
