package de.scamupdates.sources;

import java.util.Locale;

public final class NameMatcher {
    private NameMatcher() {}

    public static String normalize(String name) {
        if (name == null) return "";
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.length() > 6 && n.endsWith("plugin")) n = n.substring(0, n.length() - 6);
        return n;
    }

    public static boolean same(String a, String b) {
        String x = normalize(a), y = normalize(b);
        return !x.isEmpty() && x.equals(y);
    }
}
