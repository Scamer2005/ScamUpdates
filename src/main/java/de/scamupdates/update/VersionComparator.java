package de.scamupdates.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Toleranter Versionsvergleich fuer uneinheitliche Plugin-Versionen (1.2.3, v2.0-beta1, 5.1.0+build7, ...). */
public final class VersionComparator {
    private VersionComparator() {}

    private record Tok(boolean numeric, long num, String text) {}

    private static final Set<String> IGNORED = Set.of("final", "release", "ga", "stable");
    private static final Pattern LETTERS_DIGITS = Pattern.compile("^([a-z]+)(\\d+)$");
    private static final int RELEASE_RANK = 5;

    private static List<Tok> tokenize(String version) {
        String s = version == null ? "" : version.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^(version|ver|v)[\\s._-]*(?=\\d)", "");
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        List<Tok> out = new ArrayList<>();
        for (String part : s.split("[^a-z0-9]+")) {
            if (part.isEmpty()) continue;
            Matcher m = LETTERS_DIGITS.matcher(part);
            if (m.matches()) {
                addText(out, m.group(1));
                addNumber(out, m.group(2));
            } else if (part.chars().allMatch(Character::isDigit)) {
                addNumber(out, part);
            } else {
                addText(out, part);
            }
        }
        return out;
    }

    private static void addText(List<Tok> out, String t) {
        if (!IGNORED.contains(t)) out.add(new Tok(false, 0, t));
    }

    private static void addNumber(List<Tok> out, String digits) {
        try {
            out.add(new Tok(true, Long.parseLong(digits), digits));
        } catch (NumberFormatException e) {
            out.add(new Tok(false, 0, digits));
        }
    }

    private static int rank(String text) {
        return switch (text) {
            case "snapshot", "dev", "nightly", "canary" -> 0;
            case "alpha" -> 1;
            case "beta" -> 2;
            case "pre", "preview", "milestone" -> 3;
            case "rc", "cr" -> 4;
            default -> 10;
        };
    }

    public static int compare(String a, String b) {
        List<Tok> ta = tokenize(a), tb = tokenize(b);
        int max = Math.max(ta.size(), tb.size());
        for (int i = 0; i < max; i++) {
            int c = compareTok(i < ta.size() ? ta.get(i) : null, i < tb.size() ? tb.get(i) : null);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int compareTok(Tok x, Tok y) {
        if (x == null && y == null) return 0;
        if (x == null) return -compareTok(y, null);
        if (y == null) {
            if (x.numeric()) return x.num() == 0 ? 0 : 1;
            return rank(x.text()) < RELEASE_RANK ? -1 : 1;
        }
        if (x.numeric() && y.numeric()) return Long.compare(x.num(), y.num());
        if (x.numeric()) return numericVsText(x, y.text());
        if (y.numeric()) return -numericVsText(y, x.text());
        int rx = rank(x.text()), ry = rank(y.text());
        if (rx != ry) return Integer.compare(rx, ry);
        return x.text().compareTo(y.text());
    }

    private static int numericVsText(Tok n, String text) {
        if (rank(text) < RELEASE_RANK) return 1;
        return n.num() == 0 ? -1 : 1;
    }

    public static boolean hasDigits(String v) {
        return v != null && v.chars().anyMatch(Character::isDigit);
    }

    public static boolean isNewer(String remote, String local, boolean unknownOrderAsUpdate) {
        if (remote == null || local == null) return false;
        if (hasDigits(remote) && hasDigits(local)) return compare(remote, local) > 0;
        return unknownOrderAsUpdate && !remote.trim().equalsIgnoreCase(local.trim());
    }

    public static boolean isNewer(String remote, String local) {
        return isNewer(remote, local, false);
    }
}
