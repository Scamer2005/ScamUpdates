package de.scamupdates.net;

import de.scamupdates.logging.ScamLog;
import de.scamupdates.state.PluginState;
import de.scamupdates.state.StateManager;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Verwaltet die Internet-Erreichbarkeit getrennt vom Gesamtzustand des Plugins.
 * Bei fehlendem Internet wechselt das Plugin in ACTIVE_OFFLINE (lokale Funktionen bleiben aktiv)
 * und kehrt bei erneuter Erreichbarkeit automatisch zu ACTIVE zurueck.
 */
public final class ConnectivityGuard {

    private final StateManager state;
    private final Supplier<InternetCheck.Report> probe;
    private final ScamLog log;

    public ConnectivityGuard(StateManager state, Supplier<InternetCheck.Report> probe, ScamLog log) {
        this.state = Objects.requireNonNull(state, "state");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Fuehrt die Internetpruefung aus (blockierend, nie auf dem Hauptthread). */
    public synchronized boolean refresh() {
        if (!state.isOperational()) return false;
        InternetCheck.Report rep;
        try {
            rep = probe.get();
        } catch (RuntimeException e) {
            String msg = log.redact("Internetpruefung fehlgeschlagen: " + e.getClass().getSimpleName());
            boolean changed = state.setOnline(false, List.of(msg));
            if (changed) log.warn(msg + " - Online-Funktionen pausiert, lokale Funktionen bleiben aktiv.");
            return false;
        }
        if (rep == null) {
            boolean changed = state.setOnline(false, List.of("Kein Pruefergebnis"));
            if (changed) log.warn("Keine Internetverbindung - Online-Funktionen pausiert, lokale Funktionen bleiben aktiv.");
            return false;
        }
        log.debug("Internetpruefung: " + rep.describe());
        if (rep.verdict() == InternetCheck.Verdict.CONNECTED) {
            boolean changed = state.setOnline(true, List.of());
            if (changed) log.info("Internetverbindung aktiv - alle Online-Funktionen verfuegbar.");
            if (rep.failures() > 0) {
                log.warn("Internet erreichbar, aber " + rep.failures() + " Pruefziel(e) nicht: " + rep.describe());
            }
            return true;
        }
        String r = reason(rep.verdict());
        boolean changed = state.setOnline(false, List.of(r, rep.describe()));
        if (changed) {
            log.warn("Keine Internetverbindung (" + r + ") - Online-Funktionen pausiert, lokale Funktionen bleiben verfuegbar.");
        }
        return false;
    }

    /**
     * Prueft, ob Online-Funktionen ausgefuehrt werden duerfen. Im Zustand ACTIVE_OFFLINE wird zuvor
     * frisch geprueft, ob die Verbindung wiederhergestellt wurde.
     */
    public boolean ensureOnlineOrRefresh() {
        state.requireActive();
        if (state.isOnline()) return true;
        return refresh();
    }

    public static String reason(InternetCheck.Verdict v) {
        if (v == null) return "Keines der Pruefziele war erreichbar";
        return switch (v) {
            case DNS_ERROR -> "DNS-Fehler: Hostnamen konnten nicht aufgeloest werden";
            case TIMEOUT -> "Zeitueberschreitung bei allen Pruefzielen";
            case TLS_ERROR -> "TLS-/Zertifikatsfehler bei allen Pruefzielen (Uhrzeit, Proxy, Java-Zertifikate pruefen)";
            default -> "Keines der Pruefziele war erreichbar";
        };
    }
}
