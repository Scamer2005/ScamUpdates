package de.scamupdates.crafty;

/** Zustand der Serversteuerung fuer Statusanzeigen: Crafty (enabled) oder lokaler /restart. */
public record ControlStatus(boolean craftyEnabled, boolean ready, long time, String detail) {
    public static ControlStatus unknown(boolean craftyEnabled) {
        return new ControlStatus(craftyEnabled, false, 0, "noch nicht geprueft");
    }

    public static ControlStatus disabledLocal(boolean localReady, String detail) {
        return new ControlStatus(false, localReady, System.currentTimeMillis(), detail);
    }

    public static ControlStatus connected() {
        return new ControlStatus(true, true, System.currentTimeMillis(), "Verbunden");
    }

    public static ControlStatus craftyUnavailable(String detail) {
        return new ControlStatus(true, false, System.currentTimeMillis(), detail);
    }
}
