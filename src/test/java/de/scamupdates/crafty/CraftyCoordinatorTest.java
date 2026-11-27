package de.scamupdates.crafty;

import com.sun.net.httpserver.HttpServer;
import de.scamupdates.config.ConfigManager;
import de.scamupdates.config.CraftyConfig;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.restart.LocalRestart;
import de.scamupdates.restart.RestartService;
import de.scamupdates.security.OperationLock;
import de.scamupdates.state.PluginState;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.TransactionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CraftyCoordinatorTest {

    static final String SECRET_TOKEN = "super-secret-crafty-token-987654";

    @TempDir Path dir;
    HttpServer server;
    int port;
    final AtomicInteger apiHits = new AtomicInteger();
    final AtomicInteger authStatus = new AtomicInteger(200);
    final CopyOnWriteArrayList<LogRecord> capturedRecords = new CopyOnWriteArrayList<>();
    Logger rawLogger;
    ScamLog log;
    ExecutorService io;

    @BeforeEach
    void setUp() throws Exception {
        rawLogger = Logger.getLogger("CraftyCoordinatorTest-" + System.nanoTime());
        rawLogger.setUseParentHandlers(false);
        rawLogger.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { capturedRecords.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        log = new ScamLog(rawLogger, dir.resolve("logs"));
        io = Executors.newSingleThreadExecutor();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/servers/srv-1", ex -> {
            apiHits.incrementAndGet();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            int code = authStatus.get();
            if (code == 200 && !("Bearer " + SECRET_TOKEN).equals(auth)) code = 401;
            byte[] body = (code == 200 ? "{\"status\":\"ok\"}" : "{\"status\":\"error\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (io != null) io.shutdownNow();
    }

    @Test
    void disabledCraftyMakesZeroConnectionsAndZeroErrors() {
        CraftyCoordinator coord = new CraftyCoordinator(log);
        CraftyConfig cfg = new CraftyConfig(false, "http://127.0.0.1:" + port, "/api/v2",
                SECRET_TOKEN, "srv-1", 3000, true, null, true, true);

        CraftyCoordinator.Evaluation eval = coord.evaluateAndConnect(cfg, "restart");

        assertEquals(CraftyCoordinator.State.DISABLED_BY_CONFIG, eval.state());
        assertFalse(eval.degraded());
        assertNull(coord.client());
        assertFalse(coord.isReady());
        assertFalse(coord.isDegraded());
        assertFalse(coord.status().craftyEnabled());
        assertEquals(0, apiHits.get(), "Bei crafty.enabled: false duerfen keine API-Anfragen gesendet werden");
        assertTrue(capturedRecords.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()),
                "Keine Warnungen oder Fehler bei deaktiviertem Crafty");
    }

    @Test
    void invalidCraftyConfigDegradesOnlyCraftyWhileGlobalConfigStaysValid() throws Exception {
        String defaults = new String(getClass().getResourceAsStream("/config.yml").readAllBytes(), StandardCharsets.UTF_8);
        String userYaml = """
                config-version: 2
                crafty:
                  enabled: true
                  connection:
                    base-url: "http://crafty.local"
                    api-base-path: "/api/v2"
                    api-token: "super-secret-crafty-token-987654"
                    server-id: "bad/id"
                    timeout: 50
                    allow-http: false
                """;
        ConfigManager cm = new ConfigManager(null, log);
        ConfigManager.LoadResult lr = cm.loadFromString(userYaml, defaults);
        assertTrue(lr.ok(), "Fehlerhafte Crafty-Settings duerfen das Hauptplugin nicht deaktivieren: " + lr.problems());

        CraftyCoordinator coord = new CraftyCoordinator(log);
        CraftyCoordinator.Evaluation eval = coord.evaluateAndConnect(cm.crafty(), cm.localRestartCommand());

        assertEquals(CraftyCoordinator.State.INVALID_CONFIG, eval.state());
        assertTrue(eval.degraded());
        assertNull(coord.client());
        assertTrue(coord.isDegraded());
        assertFalse(coord.isReady());
        assertEquals(0, apiHits.get());
        assertNoSecretLeaked(eval.detail(), coord.status().detail());
    }

    @Test
    void failedCraftyAuthDegradesOnlyCraftyAndNeverLeaksToken() throws Exception {
        StateManager state = new StateManager();
        state.set(PluginState.ACTIVE, List.of());

        CraftyCoordinator coord = new CraftyCoordinator(log);
        CraftyConfig cfg = new CraftyConfig(true, "http://127.0.0.1:" + port, "/api/v2",
                SECRET_TOKEN, "srv-1", 3000, true, null, true, true);
        authStatus.set(401);

        CraftyCoordinator.Evaluation eval = coord.evaluateAndConnect(cfg, "restart");

        assertEquals(CraftyCoordinator.State.AUTH_FAILED, eval.state());
        assertTrue(eval.degraded());
        assertNull(coord.client());
        assertTrue(coord.isDegraded());
        assertTrue(state.isOperational(), "Auth-Fehler bei Crafty darf das Hauptplugin nicht deaktivieren");
        assertTrue(state.isOnline());
        assertNoSecretLeaked(eval.detail(), coord.status().detail());
    }

    @Test
    void unreachableCraftyDegradesCraftyAndRestartFailsCleanlyWithoutBreakingState() throws Exception {
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0)) { closedPort = ss.getLocalPort(); }

        StateManager state = new StateManager();
        state.set(PluginState.ACTIVE, List.of());
        ConfigManager cm = new ConfigManager(null, log);
        String defaults = new String(getClass().getResourceAsStream("/config.yml").readAllBytes(), StandardCharsets.UTF_8);
        String userYaml = """
                config-version: 2
                crafty:
                  enabled: true
                  connection:
                    base-url: "http://127.0.0.1:%d"
                    api-base-path: "/api/v2"
                    api-token: "%s"
                    server-id: "srv-1"
                    allow-http: true
                """.formatted(closedPort, SECRET_TOKEN);
        assertTrue(cm.loadFromString(userYaml, defaults).ok());

        CraftyCoordinator coord = new CraftyCoordinator(log);
        CraftyCoordinator.Evaluation eval = coord.evaluateAndConnect(cm.crafty(), cm.localRestartCommand());

        assertEquals(CraftyCoordinator.State.UNAVAILABLE, eval.state());
        assertTrue(eval.degraded());
        assertNull(coord.client());

        RestartService restart = new RestartService(state, cm, log, new OperationLock(), io,
                new TransactionStore(dir.resolve("tx.yml"), log),
                new de.scamupdates.backup.BackupManager(dir.resolve("backups"), log),
                dir.resolve("update"), coord::client, () -> "1.21.11");
        RestartService.Readiness ready = restart.readiness();
        assertFalse(ready.ready());
        assertEquals(RestartService.RestartMode.UNAVAILABLE, ready.mode());

        RestartService.Outcome outcome = restart.restart("admin").get();
        assertEquals(RestartService.Result.FAILED, outcome.result());
        assertTrue(state.isOperational(), "Fehlgeschlagener Crafty-Neustart darf das Hauptplugin nicht deaktivieren");
        assertNoSecretLeaked(eval.detail(), String.join(" ", outcome.details()));
    }

    @Test
    void craftyCanRecoverOnReevaluationAndLocalRestartWorksWhenCraftyDisabled() throws Exception {
        CraftyCoordinator coord = new CraftyCoordinator(log);
        authStatus.set(403);
        CraftyConfig cfg = new CraftyConfig(true, "http://127.0.0.1:" + port, "/api/v2",
                SECRET_TOKEN, "srv-1", 3000, true, null, true, true);
        assertEquals(CraftyCoordinator.State.AUTH_FAILED, coord.evaluateAndConnect(cfg, "restart").state());

        authStatus.set(200);
        CraftyCoordinator.Evaluation recovered = coord.evaluateAndConnect(cfg, "restart");
        assertEquals(CraftyCoordinator.State.CONNECTED, recovered.state());
        assertFalse(recovered.degraded());
        assertTrue(coord.isReady());
        assertNotNull(coord.client());

        // Jetzt lokaler Neustart bei crafty.enabled: false
        String defaults = new String(getClass().getResourceAsStream("/config.yml").readAllBytes(), StandardCharsets.UTF_8);
        ConfigManager cm = new ConfigManager(null, log);
        assertTrue(cm.loadFromString("config-version: 2\ncrafty:\n  enabled: false\n", defaults).ok());

        Files.writeString(dir.resolve("spigot.yml"), "settings:\n  restart-script: ./start.sh\n");
        Files.writeString(dir.resolve("start.sh"), "#!/bin/sh\n");
        AtomicBoolean triggered = new AtomicBoolean(false);
        LocalRestart lr = new LocalRestart(dir, "restart", () -> true, cmd -> {
            triggered.set(true);
            return true;
        });

        StateManager state = new StateManager();
        state.set(PluginState.ACTIVE_OFFLINE, List.of("offline"));
        RestartService rs = new RestartService(state, cm, log, new OperationLock(), io,
                new TransactionStore(dir.resolve("tx.yml"), log),
                new de.scamupdates.backup.BackupManager(dir.resolve("backups"), log),
                dir.resolve("update"), () -> null, () -> "1.21.11");
        rs.setLocal(lr, Runnable::run);

        RestartService.Readiness ready = rs.readiness();
        assertTrue(ready.ready());
        assertEquals(RestartService.RestartMode.LOCAL, ready.mode());
        RestartService.Outcome out = rs.restart("console").get();
        assertEquals(RestartService.Result.REQUESTED_LOCAL, out.result());
        assertTrue(triggered.get());
    }

    private void assertNoSecretLeaked(String... surfaces) throws IOException {
        for (String s : surfaces) {
            if (s != null) assertFalse(s.contains(SECRET_TOKEN), "API-Token darf nie sichtbar sein: " + s);
        }
        for (LogRecord r : capturedRecords) {
            String msg = r.getMessage();
            if (msg != null) assertFalse(msg.contains(SECRET_TOKEN), "API-Token im Log-Record gefunden");
        }
        Path logFile = dir.resolve("logs/scamupdates.log");
        if (Files.isRegularFile(logFile)) {
            String content = Files.readString(logFile);
            assertFalse(content.contains(SECRET_TOKEN), "API-Token in scamupdates.log gefunden");
        }
    }
}
