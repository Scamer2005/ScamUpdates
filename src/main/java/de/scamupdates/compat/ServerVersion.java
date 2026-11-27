package de.scamupdates.compat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minecraft-Version, tolerant gegenueber 1.x.y und dem neuen jahresbasierten Schema (z. B. 26.1). */
public final class ServerVersion implements Comparable<ServerVersion> {
    private static final Pattern P = Pattern.compile("^\\s*(\\d+(?:\\.\\d+){0,3})");

    private final int[] parts;
    private final String text;

    private ServerVersion(int[] parts, String text) {
        this.parts = parts;
        this.text = text;
    }

    public static Optional<ServerVersion> parse(String raw) {
        if (raw == null) return Optional.empty();
        Matcher m = P.matcher(raw);
        if (!m.find()) return Optional.empty();
        String[] seg = m.group(1).split("\\.");
        int[] parts = new int[seg.length];
        try {
            for (int i = 0; i < seg.length; i++) parts[i] = Integer.parseInt(seg[i]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(new ServerVersion(parts, m.group(1)));
    }

    public static ServerVersion of(String raw) {
        return parse(raw).orElseThrow(() -> new IllegalArgumentException("Keine Version: " + raw));
    }

    @Override
    public int compareTo(ServerVersion o) {
        int n = Math.max(parts.length, o.parts.length);
        for (int i = 0; i < n; i++) {
            int a = i < parts.length ? parts[i] : 0, b = i < o.parts.length ? o.parts[i] : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    public boolean isAtLeast(String other) { return compareTo(of(other)) >= 0; }

    public boolean isNewerThan(String other) { return compareTo(of(other)) > 0; }

    @Override public boolean equals(Object o) { return o instanceof ServerVersion v && compareTo(v) == 0; }

    @Override public int hashCode() {
        int h = 1;
        int last = parts.length;
        while (last > 1 && parts[last - 1] == 0) last--;
        for (int i = 0; i < last; i++) h = 31 * h + parts[i];
        return h;
    }

    @Override public String toString() { return text; }
}
