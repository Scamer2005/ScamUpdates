package de.scamupdates.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import de.scamupdates.logging.ScamLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP-Zugriff fuer Updatequellen und Downloads: Timeouts, Retries, Rate-Limit-Sperre, HTTPS-Zwang. */
public final class HttpService {

    public static final class HttpStatusException extends IOException {
        private final int status;
        private final long retryAfterSeconds;

        public HttpStatusException(int status, String url, long retryAfterSeconds) {
            super("HTTP " + status + " von " + hostOf(url) + (status == 429 ? " (Rate-Limit, erneut in ca. " + retryAfterSeconds + "s)" : ""));
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int status() { return status; }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    private static final int MAX_BODY = 8 * 1024 * 1024;

    private final HttpClient client;
    private final String userAgent;
    private final Duration timeout;
    private final int retries;
    private final boolean allowHttp;
    private final ScamLog log;
    private final Map<String, Long> blockedUntil = new ConcurrentHashMap<>();

    public HttpService(String userAgent, Duration timeout, int retries, boolean allowHttp, ScamLog log) {
        this.userAgent = userAgent;
        this.timeout = timeout;
        this.retries = Math.max(0, retries);
        this.allowHttp = allowHttp;
        this.log = log;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL) // folgt nie einem Downgrade https -> http
                .connectTimeout(timeout)
                .build();
    }

    static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "?" : h;
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }

    private URI checkUri(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungueltige URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (uri.getHost() == null) throw new IOException("URL ohne Host");
        if (!scheme.equals("https") && !(scheme.equals("http") && allowHttp)) {
            throw new IOException("Unsicheres oder unbekanntes Protokoll abgelehnt: " + scheme);
        }
        return uri;
    }

    public String getString(String url, Map<String, String> headers) throws IOException, InterruptedException {
        URI uri = checkUri(url);
        String host = uri.getHost();
        Long until = blockedUntil.get(host);
        long now = System.currentTimeMillis();
        if (until != null && until > now) {
            throw new HttpStatusException(429, url, (until - now) / 1000 + 1);
        }
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (attempt > 0) Thread.sleep(500L * attempt);
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/json, text/plain, */*")
                        .GET();
                headers.forEach(b::header);
                HttpResponse<InputStream> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = resp.body()) {
                    int code = resp.statusCode();
                    if (code == 429 || (code == 403 && "0".equals(resp.headers().firstValue("x-ratelimit-remaining").orElse("")))) {
                        long wait = resp.headers().firstValue("retry-after").map(HttpService::parseLong).orElse(-1L);
                        if (wait < 0) {
                            long reset = resp.headers().firstValue("x-ratelimit-reset").map(HttpService::parseLong).orElse(0L);
                            wait = reset > 0 ? Math.max(1, reset - System.currentTimeMillis() / 1000) : 60;
                        }
                        blockedUntil.put(host, System.currentTimeMillis() + Math.min(wait, 3600) * 1000);
                        log.warn("Rate-Limit bei " + host + ", Anfragen werden " + wait + "s pausiert.");
                        throw new HttpStatusException(429, url, wait);
                    }
                    if (code >= 500) {
                        last = new HttpStatusException(code, url, 0);
                        continue;
                    }
                    if (code < 200 || code >= 300) throw new HttpStatusException(code, url, 0);
                    return new String(in.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
                }
            } catch (HttpStatusException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                log.debug("Anfrage an " + host + " fehlgeschlagen (Versuch " + (attempt + 1) + "): " + e.getClass().getSimpleName());
            }
        }
        throw last != null ? last : new IOException("Anfrage fehlgeschlagen");
    }

    public JsonElement getJson(String url, Map<String, String> headers) throws IOException, InterruptedException {
        String body = getString(url, headers);
        try {
            return JsonParser.parseString(body);
        } catch (JsonParseException e) {
            throw new IOException("Ungueltige JSON-Antwort von " + hostOf(url));
        }
    }

    /** Laedt in die Zieldatei (Aufrufer verwaltet Temp-Datei und Aufraeumen). Gibt die Byte-Anzahl zurueck. */
    public long download(String url, Path target, long maxBytes, Duration totalTimeout) throws IOException, InterruptedException {
        URI uri = checkUri(url);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", userAgent).GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = resp.body()) {
            if (resp.statusCode() != 200) throw new HttpStatusException(resp.statusCode(), url, 0);
            String finalScheme = resp.uri().getScheme() == null ? "" : resp.uri().getScheme().toLowerCase();
            if (!finalScheme.equals("https") && !allowHttp) throw new IOException("Weiterleitung auf unsicheres Protokoll abgelehnt");
            long declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > maxBytes) throw new IOException("Datei zu gross (" + declared + " Bytes, Limit " + maxBytes + ")");
            long deadline = System.nanoTime() + totalTimeout.toNanos();
            long total = 0;
            byte[] buf = new byte[16384];
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) throw new IOException("Download ueberschreitet das Groessenlimit (" + maxBytes + " Bytes)");
                    if (System.nanoTime() > deadline) throw new IOException("Download-Zeitlimit ueberschritten");
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buf, 0, n);
                }
            }
            return total;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
