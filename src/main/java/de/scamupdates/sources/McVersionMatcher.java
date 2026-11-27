package de.scamupdates.sources;

import de.scamupdates.update.VersionComparator;

import java.util.List;
import java.util.Locale;

/** Prueft, ob eine von einer Plattform angegebene Minecraft-Version/-Spanne zur Serverversion passt. */
public final class McVersionMatcher {
    private McVersionMatcher() {}

    public static boolean matches(String dep, String mc) {
        if (dep == null || mc == null) return false;
        String d = dep.trim().toLowerCase(Locale.ROOT);
        String m = mc.trim().toLowerCase(Locale.ROOT);
        if (d.isEmpty()) return false;
        if (d.equals(m)) return true;
        int dash = d.indexOf('-');
        if (dash > 0 && dash < d.length() - 1) {
            String lo = d.substring(0, dash).trim();
            String hi = d.substring(dash + 1).trim();
            boolean aboveLow = VersionComparator.compare(m, lo) >= 0;
            boolean belowHigh = VersionComparator.compare(m, hi) <= 0 || prefixMatch(hi, m);
            return aboveLow && belowHigh;
        }
        if (d.endsWith(".x") || d.endsWith(".*")) {
            return prefixMatch(d.substring(0, d.length() - 2), m);
        }
        return prefixMatch(d, m);
    }

    private static boolean prefixMatch(String prefix, String mc) {
        return mc.equals(prefix) || mc.startsWith(prefix + ".");
    }

    public static boolean anyMatches(List<String> deps, String mc) {
        for (String d : deps) if (matches(d, mc)) return true;
        return false;
    }
}
