package de.scamupdates.crafty;

import de.scamupdates.config.CraftyConfig;
import de.scamupdates.logging.ScamLog;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Objects;

/**
 * Kapselt den Lebenszyklus der optionalen Crafty-Controller-Integration.
 * Crafty-Fehler (fehlende/fehlerhafte Konfiguration, TLS, Auth, Unerreichbarkeit) deaktivieren
 * ausschliesslich diese Integration, niemals das Hauptplugin.
 */
public final class CraftyCoordinator {

    public enum State { DISABLED_BY_CONFIG, CONNECTED, INVALID_CONFIG, AUTH_FAILED, UNAVAILABLE }

    public record Evaluation(State state, boolean degraded, String detail) {}

    @FunctionalInterface
    public interface ClientFactory {
        CraftyClient create(CraftyConfig config, ScamLog log) throws IOException, GeneralSecurityException;
    }

    private final ScamLog log;
    private final ClientFactory factory;
    private volatile CraftyClient client;
    private volatile ControlStatus status = ControlStatus.unknown(false);
    private volatile State state = State.DISABLED_BY_CONFIG;

    public CraftyCoordinator(ScamLog log) {
        this(log, CraftyClient::new);
    }

    public CraftyCoordinator(ScamLog log, ClientFactory factory) {
        this.log = Objects.requireNonNull(log, "log");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public CraftyClient client() { return client; }
    public ControlStatus status() { return status; }
    public State state() { return state; }
    public boolean isReady() { return state == State.CONNECTED && client != null; }
    public boolean isDegraded() { return status.craftyEnabled() && !status.ready(); }

    public void setLocalStatus(ControlStatus localStatus) {
        if (localStatus != null && !localStatus.craftyEnabled()) {
            this.status = localStatus;
        }
    }

    public synchronized void reset(boolean craftyEnabled) {
        this.client = null;
        this.status = ControlStatus.unknown(craftyEnabled);
        this.state = craftyEnabled ? State.UNAVAILABLE : State.DISABLED_BY_CONFIG;
    }

    /**
     * Prueft die Crafty-Konfiguration und (falls aktiviert) Verbindung + Authentifizierung.
     * Bei crafty.enabled=false werden keinerlei Netzwerkanfragen oder Fehlermeldungen erzeugt.
     */
    public synchronized Evaluation evaluateAndConnect(CraftyConfig cfg, String localRestartCommand) {
        if (cfg == null || !cfg.enabled()) {
            this.client = null;
            this.state = State.DISABLED_BY_CONFIG;
            String cmd = localRestartCommand == null || localRestartCommand.isBlank() ? "restart" : localRestartCommand.trim();
            String msg = "Crafty deaktiviert, lokaler /" + cmd;
            this.status = ControlStatus.disabledLocal(false, msg);
            return new Evaluation(State.DISABLED_BY_CONFIG, false, msg);
        }

        log.registerSecret(cfg.apiToken());

        List<String> missing = cfg.missingSettings();
        if (!missing.isEmpty()) {
            String msg = log.redact("Crafty-Konfiguration unvollstaendig: " + String.join(", ", missing));
            return degrade(State.INVALID_CONFIG, msg, true);
        }
        List<String> format = cfg.formatProblems();
        if (!format.isEmpty()) {
            String msg = log.redact("Crafty-Konfiguration fehlerhaft: " + String.join("; ", format));
            return degrade(State.INVALID_CONFIG, msg, true);
        }

        CraftyClient candidate;
        try {
            candidate = factory.create(cfg, log);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            String msg = log.redact("Crafty-Client konnte nicht initialisiert werden: " + e.getMessage());
            return degrade(State.INVALID_CONFIG, msg, true);
        }

        if (!cfg.verifySsl()) {
            log.warn("crafty.connection.verify-ssl ist false: Die Crafty-Verbindung ist NICHT gegen Abhoeren geschuetzt!");
        }

        CraftyClient.Result r = candidate.verifyConnection();
        if (r.ok()) {
            this.client = candidate;
            this.state = State.CONNECTED;
            this.status = ControlStatus.connected();
            log.info("Crafty Controller verbunden.");
            return new Evaluation(State.CONNECTED, false, "Verbunden");
        }

        return switch (r.status()) {
            case AUTH_FAILED -> degrade(State.AUTH_FAILED, log.redact("Authentifizierung fehlgeschlagen: " + r.message()), true);
            case SERVER_NOT_FOUND -> degrade(State.INVALID_CONFIG,
                    log.redact("Crafty kennt keinen Server mit der konfigurierten server-id (oder api-base-path ist falsch). " + r.message()), true);
            case TLS_ERROR -> degrade(State.UNAVAILABLE,
                    log.redact("TLS-/Zertifikatsfehler: " + r.message()), true);
            default -> degrade(State.UNAVAILABLE, log.redact(r.message()), true);
        };
    }

    private Evaluation degrade(State target, String redactedMsg, boolean logWarning) {
        this.client = null;
        this.state = target;
        this.status = ControlStatus.craftyUnavailable(redactedMsg);
        if (logWarning) {
            log.warn("Crafty-Integration deaktiviert (" + target + "): " + redactedMsg
                    + " - Alle uebrigen ScamUpdates-Funktionen bleiben aktiv.");
        }
        return new Evaluation(target, true, redactedMsg);
    }
}
