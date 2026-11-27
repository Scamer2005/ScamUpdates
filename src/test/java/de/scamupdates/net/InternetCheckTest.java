package de.scamupdates.net;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.CompletionException;

import static de.scamupdates.net.InternetCheck.*;
import static org.junit.jupiter.api.Assertions.*;

class InternetCheckTest {

    private static ProbeResult p(String t, Outcome o) { return new ProbeResult(t, o, ""); }

    @Test
    void classifiesErrorsThroughWrappedCauses() {
        ConnectException wrapped = new ConnectException("x");
        wrapped.initCause(new UnknownHostException("nohost"));
        assertEquals(Outcome.DNS_FAILURE, outcomeOf(new CompletionException(wrapped)));
        assertEquals(Outcome.TLS_FAILURE, outcomeOf(new CompletionException(new SSLHandshakeException("bad cert"))));
        assertEquals(Outcome.TIMEOUT, outcomeOf(new HttpTimeoutException("timed out")));
        assertEquals(Outcome.UNREACHABLE, outcomeOf(new ConnectException("refused")));
    }

    @Test
    void singleReachableTargetMeansConnected() {
        Report r = evaluate(List.of(p("a", Outcome.DNS_FAILURE), p("b", Outcome.OK), p("c", Outcome.TIMEOUT)));
        assertEquals(Verdict.CONNECTED, r.verdict());
        assertEquals(2, r.failures());
    }

    @Test
    void allFailuresAreDistinguished() {
        assertEquals(Verdict.DNS_ERROR, evaluate(List.of(p("a", Outcome.DNS_FAILURE), p("b", Outcome.DNS_FAILURE))).verdict());
        assertEquals(Verdict.TIMEOUT, evaluate(List.of(p("a", Outcome.TIMEOUT), p("b", Outcome.TIMEOUT))).verdict());
        assertEquals(Verdict.TLS_ERROR, evaluate(List.of(p("a", Outcome.TLS_FAILURE))).verdict());
        assertEquals(Verdict.NO_INTERNET, evaluate(List.of(p("a", Outcome.UNREACHABLE), p("b", Outcome.DNS_FAILURE))).verdict());
        assertEquals(Verdict.NO_INTERNET, evaluate(List.of()).verdict());
    }
}
