package de.scamupdates.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public record CraftyConfig(boolean enabled, String baseUrl, String apiBasePath, String apiToken,
                           String serverId, int timeoutMs, boolean verifySsl, Path caFile,
                           boolean allowHttp, boolean restartEnabled) {

    public static final String TOKEN_PLACEHOLDER = "HIER_API_TOKEN_EINTRAGEN";
    public static final String SERVER_ID_PLACEHOLDER = "HIER_SERVER_ID_EINTRAGEN";
    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** Fehlende Pflichtangaben (nur relevant bei crafty.enabled: true). Leer = vollstaendig oder Crafty deaktiviert. */
    public List<String> missingSettings() {
        List<String> missing = new ArrayList<>();
        if (!enabled) return missing;
        if (isBlank(baseUrl) || isPlaceholderUrl(baseUrl)) missing.add("Crafty-URL (crafty.connection.base-url)");
        if (isBlank(apiBasePath)) missing.add("API-Basisadresse (crafty.connection.api-base-path)");
        if (isBlank(apiToken) || TOKEN_PLACEHOLDER.equals(apiToken.trim())) missing.add("API-Token (crafty.connection.api-token)");
        if (isBlank(serverId) || SERVER_ID_PLACEHOLDER.equals(serverId.trim())) missing.add("Server-ID (crafty.connection.server-id)");
        return missing;
    }

    /** Formale Probleme bei vorhandenen Angaben (nur sinnvoll, wenn missingSettings() leer ist). */
    public List<String> formatProblems() {
        List<String> problems = new ArrayList<>();
        if (!enabled) return problems;
        String lower = baseUrl == null ? "" : baseUrl.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            problems.add("crafty.connection.base-url muss mit https:// beginnen");
        } else if (lower.startsWith("http://") && !allowHttp) {
            problems.add("crafty.connection.base-url nutzt http://; setze allow-http: true (nicht empfohlen) oder verwende https://");
        }
        if (serverId != null && !SERVER_ID.matcher(serverId.trim()).matches()) {
            problems.add("crafty.connection.server-id enthaelt unzulaessige Zeichen (erlaubt: Buchstaben, Ziffern, Bindestrich)");
        }
        if (timeoutMs < 1000 || timeoutMs > 120000) {
            problems.add("crafty.connection.timeout muss zwischen 1000 und 120000 ms liegen");
        }
        return problems;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static boolean isPlaceholderUrl(String url) {
        String l = url.toLowerCase(Locale.ROOT);
        return l.contains("example.com") || l.contains("example.org");
    }

    @Override
    public String toString() {
        return "CraftyConfig[baseUrl=" + baseUrl + ", apiBasePath=" + apiBasePath + ", apiToken=***, serverId=" + serverId
                + ", timeoutMs=" + timeoutMs + ", verifySsl=" + verifySsl + "]";
    }
}
