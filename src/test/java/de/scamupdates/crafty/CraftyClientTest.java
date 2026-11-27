package de.scamupdates.crafty;

import com.sun.net.httpserver.HttpServer;
import de.scamupdates.config.CraftyConfig;
import de.scamupdates.logging.ScamLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CraftyClientTest {
    static final String TOKEN = "tok-secret-123456";
    HttpServer server;
    int port;
    final AtomicInteger restartStatus = new AtomicInteger(200);
    final AtomicReference<String> restartBody = new AtomicReference<>("{\"status\":\"ok\"}");
    final AtomicInteger restartCalls = new AtomicInteger();
    final AtomicReference<String> lastAuth = new AtomicReference<>();
    ScamLog log = new ScamLog(Logger.getLogger("test"), null);

    @BeforeEach
    void start() throws IOException {
        log.registerSecret(TOKEN);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/servers/abc", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            boolean authed = ("Bearer " + TOKEN).equals(lastAuth.get());
            int code = authed ? 200 : 401;
            send(ex, code, authed ? "{\"status\":\"ok\"}" : "{\"status\":\"error\"}");
        });
        server.createContext("/api/v2/servers/abc/action/restart_server", ex -> {
            restartCalls.incrementAndGet();
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{}"); return; }
            send(ex, restartStatus.get(), restartBody.get());
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private CraftyClient client(String token, String serverId, int p) throws Exception {
        return new CraftyClient(new CraftyConfig(true, "http://127.0.0.1:" + p + "/", "/api/v2/", token, serverId, 3000, true, null, true, true), log);
    }

    @Test
    void verifyConnectionSucceedsWithBearerToken() throws Exception {
        CraftyClient.Result r = client(TOKEN, "abc", port).verifyConnection();
        assertEquals(CraftyClient.Status.OK, r.status(), r.message());
        assertEquals("Bearer " + TOKEN, lastAuth.get());
    }

    @Test
    void wrongTokenIsAuthFailureWithoutLeakingToken() throws Exception {
        CraftyClient.Result r = client("falsch-token-xyz", "abc", port).verifyConnection();
        assertEquals(CraftyClient.Status.AUTH_FAILED, r.status());
        assertFalse(r.message().contains("falsch-token-xyz"));
    }

    @Test
    void unknownServerIdIsReported() throws Exception {
        CraftyClient.Result r = client(TOKEN, "doesnotexist", port).verifyConnection();
        assertEquals(CraftyClient.Status.SERVER_NOT_FOUND, r.status());
    }

    @Test
    void restartUsesPostAndReportsSuccess() throws Exception {
        CraftyClient.Result r = client(TOKEN, "abc", port).restartServer();
        assertTrue(r.ok(), r.message());
        assertEquals(1, restartCalls.get());
    }

    @Test
    void rejectedRestartIsNeverReportedAsSuccess() throws Exception {
        restartStatus.set(500);
        restartBody.set("{\"status\":\"error\"}");
        assertEquals(CraftyClient.Status.REJECTED, client(TOKEN, "abc", port).restartServer().status());

        restartStatus.set(200);
        restartBody.set("{\"status\":\"error\",\"error\":\"SERVER_NOT_RUNNING\"}");
        CraftyClient.Result r = client(TOKEN, "abc", port).restartServer();
        assertEquals(CraftyClient.Status.REJECTED, r.status());
        assertTrue(r.message().contains("SERVER_NOT_RUNNING"));

        restartStatus.set(403);
        assertEquals(CraftyClient.Status.AUTH_FAILED, client(TOKEN, "abc", port).restartServer().status());
    }

    @Test
    void unreachableCraftyIsReported() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) { closed = s.getLocalPort(); }
        CraftyClient.Result r = client(TOKEN, "abc", closed).restartServer();
        assertEquals(CraftyClient.Status.UNREACHABLE, r.status());
        assertFalse(r.ok());
    }

    @Test
    void httpIsRejectedUnlessExplicitlyAllowed() {
        CraftyConfig cfg = new CraftyConfig(true, "http://127.0.0.1:" + port, "/api/v2", TOKEN, "abc", 3000, true, null, false, true);
        assertThrows(IOException.class, () -> new CraftyClient(cfg, log));
        assertFalse(cfg.formatProblems().isEmpty());
    }

    @Test
    void tokenNeverAppearsInToStringOrLogs() {
        CraftyConfig cfg = new CraftyConfig(true, "https://c", "/api/v2", TOKEN, "abc", 3000, true, null, false, true);
        assertFalse(cfg.toString().contains(TOKEN));
        assertFalse(log.redact("Fehler bei Authorization: Bearer " + TOKEN).contains(TOKEN));
    }
}
