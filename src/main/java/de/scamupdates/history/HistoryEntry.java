package de.scamupdates.history;

import java.util.List;

/**
 * Ein Eintrag im Update-Protokoll.
 *
 * @param result OK, WARN, FAILED oder INFO
 */
public record HistoryEntry(long time, String action, String result, String actor, String mcTarget,
                           String message, List<Detail> details) {

    /** Einzelergebnis je Plugin. */
    public record Detail(String plugin, String from, String to, String status) {}

    public static HistoryEntry of(String action, String result, String actor, String message) {
        return new HistoryEntry(System.currentTimeMillis(), action, result, actor, "", message, List.of());
    }

    public HistoryEntry withDetails(List<Detail> d) {
        return new HistoryEntry(time, action, result, actor, mcTarget, message, List.copyOf(d));
    }

    public HistoryEntry withMcTarget(String mc) {
        return new HistoryEntry(time, action, result, actor, mc == null ? "" : mc, message, details);
    }
}
