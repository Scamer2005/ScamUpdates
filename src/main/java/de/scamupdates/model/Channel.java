package de.scamupdates.model;

import java.util.Locale;

public enum Channel {
    STABLE, BETA, DEVELOPMENT;

    /** Ein konfigurierter Maximalkanal erlaubt alle stabileren Kanaele. */
    public boolean allows(Channel release) {
        return release.ordinal() <= this.ordinal();
    }

    public static Channel parse(String value, Channel fallback) {
        if (value == null || value.isBlank()) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "stable", "release" -> STABLE;
            case "beta" -> BETA;
            case "development", "dev", "snapshot", "alpha" -> DEVELOPMENT;
            default -> fallback;
        };
    }

    public static Channel fromName(String name) {
        if (name == null) return STABLE;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("snapshot") || n.contains("dev") || n.contains("alpha") || n.contains("nightly")) return DEVELOPMENT;
        if (n.contains("beta") || n.contains("rc") || n.contains("pre")) return BETA;
        return STABLE;
    }
}
