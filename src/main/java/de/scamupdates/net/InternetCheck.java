package de.scamupdates.net;

import javax.net.ssl.SSLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class InternetCheck {

    public enum Outcome { OK, DNS_FAILURE, TIMEOUT, TLS_FAILURE, UNREACHABLE }

    public enum Verdict { CONNECTED, NO_INTERNET, DNS_ERROR, TIMEOUT, TLS_ERROR }

    public record ProbeResult(String target, Outcome outcome, String detail) {}

    public record Report(Verdict verdict, List<ProbeResult> probes) {
        public String describe() {
            return probes.stream().map(p -> p.target() + " -> " + p.outcome() + (p.detail().isEmpty() ? "" : " (" + p.detail() + ")"))
                    .collect(Collectors.joining("; "));
        }
        public long failures() { return probes.stream().filter(p -> p.outcome() != Outcome.OK).count(); }
    }

    private final List<String> targets;
    private final Duration timeout;
    private final String userAgent;

    public InternetCheck(List<String> targets, Duration timeout, String userAgent) {
        this.targets = targets;
        this.timeout = timeout;
        this.userAgent = userAgent;
    }

    /** Fuehrt alle Pruefziele parallel aus. Blockiert den aufrufenden Thread -> nie auf dem Hauptthread aufrufen. */
    public Report run() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
        List<CompletableFuture<ProbeResult>> futures = new ArrayList<>();
        for (String target : targets) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(target)).timeout(timeout).header("User-Agent", userAgent).GET().build();
            futures.add(client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .handle((resp, err) -> err == null
                            ? new ProbeResult(target, Outcome.OK, "HTTP " + resp.statusCode())
                            : classify(target, err)));
        }
        List<ProbeResult> results = new ArrayList<>();
        for (CompletableFuture<ProbeResult> f : futures) results.add(f.join());
        return evaluate(results);
    }

    public static ProbeResult classify(String target, Throwable error) {
        return new ProbeResult(target, outcomeOf(error), error.getClass().getSimpleName());
    }

    /** Untersucht die gesamte Ursachenkette (das JDK verpackt Fehler haeufig in ConnectException). */
    public static Outcome outcomeOf(Throwable error) {
        boolean dns = false, tls = false, timeout = false;
        Throwable t = error;
        int depth = 0;
        while (t != null && depth++ < 12) {
            if (t instanceof UnknownHostException) dns = true;
            if (t instanceof SSLException) tls = true;
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) timeout = true;
            t = t.getCause();
        }
        if (dns) return Outcome.DNS_FAILURE;
        if (tls) return Outcome.TLS_FAILURE;
        if (timeout) return Outcome.TIMEOUT;
        return Outcome.UNREACHABLE;
    }

    /** Mindestens ein erreichbares Ziel = Internet vorhanden; einzelne Ausfaelle sind kein Gesamtausfall. */
    public static Report evaluate(List<ProbeResult> probes) {
        if (probes.stream().anyMatch(p -> p.outcome() == Outcome.OK)) {
            return new Report(Verdict.CONNECTED, probes);
        }
        if (probes.isEmpty()) return new Report(Verdict.NO_INTERNET, probes);
        Outcome first = probes.get(0).outcome();
        boolean allSame = probes.stream().allMatch(p -> p.outcome() == first);
        Verdict v = Verdict.NO_INTERNET;
        if (allSame) {
            v = switch (first) {
                case DNS_FAILURE -> Verdict.DNS_ERROR;
                case TIMEOUT -> Verdict.TIMEOUT;
                case TLS_FAILURE -> Verdict.TLS_ERROR;
                default -> Verdict.NO_INTERNET;
            };
        }
        return new Report(v, probes);
    }
}
