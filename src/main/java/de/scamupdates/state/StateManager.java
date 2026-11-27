package de.scamupdates.state;

import java.util.List;

/**
 * Zentraler Plugin-Status.
 * Lokale Aktionen benoetigen {@link #requireActive()} (ACTIVE oder ACTIVE_OFFLINE);
 * Internet-Aktionen zusaetzlich {@link #requireOnline()} (nur ACTIVE).
 */
public final class StateManager {

    public static final class OfflineException extends IllegalStateException {
        public OfflineException(String message) { super(message); }
    }

    private volatile PluginState state = PluginState.STARTING;
    private volatile List<String> details = List.of();

    public PluginState state() { return state; }
    public List<String> details() { return details; }
    public boolean isOperational() { return state.isOperational(); }
    public boolean isOnline() { return state.isOnline(); }
    public boolean isActive() { return isOperational(); }

    public synchronized void set(PluginState newState, List<String> newDetails) {
        this.details = List.copyOf(newDetails);
        this.state = newState;
    }

    /** Wechselt nur im betriebsbereiten Zustand zwischen ACTIVE und ACTIVE_OFFLINE (ueberschreibt nie DISABLED_*). */
    public synchronized boolean setOnline(boolean online, List<String> offlineDetails) {
        if (!state.isOperational()) return false;
        PluginState target = online ? PluginState.ACTIVE : PluginState.ACTIVE_OFFLINE;
        boolean changed = this.state != target;
        this.details = online ? List.of() : List.copyOf(offlineDetails);
        this.state = target;
        return changed;
    }

    public void requireActive() {
        if (!isOperational()) {
            throw new IllegalStateException("ScamUpdates ist nicht aktiv (" + state + ")");
        }
    }

    public void requireOnline() {
        requireActive();
        if (!isOnline()) {
            String reason = details.isEmpty() ? "Keine Internetverbindung" : details.get(0);
            throw new OfflineException(reason);
        }
    }
}
