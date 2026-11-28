package de.scamupdates.compat;

import org.bukkit.Bukkit;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kompatibilitaetsstatus. "TESTED" gilt nur fuer Versionen, die der Betreiber in compat.tested-versions eintraegt
 * (der Code behauptet von sich aus keine getestete Unterstuetzung). Unterstuetzte Plattformfamilien sind
 * Paper 1.20.x, 1.21.x und 26.x. Unbekannte zukuenftige Hauptversionen (z. B. 1.22+, 27.x) fuehren nicht zum
 * Absturz, sondern zum Status UNSUPPORTED_NEWER.
 */
public record CompatInfo(String minecraft, int paperBuild, Status status, String detail) {

    public enum Status { TESTED, UNTESTED, TOO_OLD, UNSUPPORTED_NEWER, UNKNOWN }

    public static final String MINIMUM = "1.20";
    /** Unterstuetzte klassische 1.x-Reihen: 1.20.x und 1.21.x. */
    public static final String MAX_LEGACY_SUPPORTED = "1.21";
    /** Unterstuetzt wird einschliesslich Paper 26.x; unbekannte zukuenftige Hauptversionen (z. B. 27.x) bleiben ausgeschlossen. */
    public static final String MAX_SUPPORTED = "26";
    /** Neueste statisch gepruefte Version. */
    public static final String KNOWN_MAX = "26.3";

    private static final Pattern BUILD = Pattern.compile("(?:\\.build\\.|-)(\\d+)(?:-|$)");

    public static CompatInfo detect(Collection<String> testedVersions) {
        String mc;
        String full;
        try {
            mc = Bukkit.getMinecraftVersion();
            full = Bukkit.getVersion();
        } catch (RuntimeException | LinkageError e) {
            return new CompatInfo("?", -1, Status.UNKNOWN, "Minecraft-Version konnte nicht ermittelt werden");
        }
        return evaluate(mc, full, testedVersions);
    }

    /**
     * Prueft schemabewusst, ob eine Version in einer der unterstuetzten Plattformfamilien liegt:
     * klassisches Schema 1.20.x / 1.21.x oder jahresbasiertes Schema 26.x.
     */
    public static boolean isSupportedVersion(ServerVersion ver) {
        if (ver == null) return false;
        int major = ver.major();
        int minor = ver.minor();
        if (major == 1) {
            return minor == 20 || minor == 21;
        }
        return major == 26;
    }

    public static CompatInfo evaluate(String mc, String bukkitVersion, Collection<String> tested) {
        int build = -1;
        if (bukkitVersion != null) {
            Matcher m = BUILD.matcher(bukkitVersion);
            if (m.find()) {
                try { build = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) { build = -1; }
            }
        }
        Optional<ServerVersion> v = ServerVersion.parse(mc);
        if (v.isEmpty()) return new CompatInfo(String.valueOf(mc), build, Status.UNKNOWN, "Versionsformat nicht erkannt: " + mc);
        ServerVersion ver = v.get();
        if (ver.major() < 1 || (ver.major() == 1 && ver.minor() < 20)) {
            return new CompatInfo(mc, build, Status.TOO_OLD, "Minecraft " + mc + " ist aelter als " + MINIMUM + " und wird nicht unterstuetzt");
        }
        if (!isSupportedVersion(ver)) {
            return new CompatInfo(mc, build, Status.UNSUPPORTED_NEWER,
                    "Minecraft " + mc + " liegt ausserhalb der unterstuetzten Plattformen (1.20.x, 1.21.x, 26.x) und wird nicht unterstuetzt. Das Plugin laeuft weiter, ungetestet und ohne Garantie.");
        }
        for (String t : tested) {
            if (t != null && ServerVersion.parse(t).map(tv -> tv.equals(ver)).orElse(false)) {
                return new CompatInfo(mc, build, Status.TESTED, "Vom Betreiber als getestet eingetragen");
            }
        }
        return new CompatInfo(mc, build, Status.UNTESTED, "Im unterstuetzten Bereich, aber nicht als getestet eingetragen");
    }

    public boolean isSupported() { return status == Status.TESTED || status == Status.UNTESTED; }

    public String statusKey() { return status.name().toLowerCase(Locale.ROOT).replace('_', '-'); }
}
