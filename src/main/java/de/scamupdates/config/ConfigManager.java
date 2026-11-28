package de.scamupdates.config;

import de.scamupdates.logging.ScamLog;
import de.scamupdates.model.Channel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

public final class ConfigManager {

    public record LoadResult(boolean ok, List<String> problems) {}

    private static final Set<String> SOURCE_IDS = Set.of("modrinth", "hangar", "spigot", "github", "jenkins", "direct");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final JavaPlugin plugin;
    private final ScamLog log;
    private volatile YamlConfiguration cfg = new YamlConfiguration();
    private volatile YamlConfiguration defaults = new YamlConfiguration();

    public ConfigManager(JavaPlugin plugin, ScamLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    // ------------------------------------------------------------------ laden

    public LoadResult load() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) plugin.saveResource("config.yml", false);
        YamlConfiguration def;
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return new LoadResult(false, List.of("Interne Standard-Config fehlt in der JAR"));
            def = new YamlConfiguration();
            def.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            return new LoadResult(false, List.of("Interne Standard-Config nicht lesbar: " + e.getMessage()));
        }
        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            return new LoadResult(false, List.of("config.yml ist kein gueltiges YAML oder nicht lesbar: " + firstLine(e.getMessage())));
        }
        int userVersion = user.getInt("config-version", 0);
        int defVersion = def.getInt("config-version", 1);
        if (userVersion > defVersion) {
            return new LoadResult(false, List.of("config-version " + userVersion + " ist neuer als von diesem Plugin unterstuetzt (" + defVersion + ")"));
        }
        ConfigMigrator.Result mr = ConfigMigrator.migrate(user, def);
        if (mr.changed()) {
            try {
                Path bak = file.toPath().resolveSibling("config.yml.bak-v" + mr.fromVersion());
                Files.copy(file.toPath(), bak, StandardCopyOption.REPLACE_EXISTING);
                user.save(file);
                log.info("Config migriert (v" + mr.fromVersion() + " -> v" + mr.toVersion() + "), " + mr.addedKeys().size()
                        + " neue Einstellung(en) ergaenzt. Sicherung: " + bak.getFileName());
            } catch (IOException e) {
                return new LoadResult(false, List.of("Config-Migration konnte nicht gespeichert werden: " + e.getMessage()));
            }
        }
        this.defaults = def;
        this.cfg = user;
        log.setDebug(user.getBoolean("general.debug", false));
        List<String> problems = validate(user);
        return new LoadResult(problems.isEmpty(), problems);
    }

    /** Laedt und validiert Konfiguration aus YAML-Strings (ohne Bukkit-JavaPlugin, z. B. fuer Tests). */
    public LoadResult loadFromString(String userYaml, String defaultsYaml) {
        YamlConfiguration def = new YamlConfiguration();
        try {
            def.loadFromString(defaultsYaml == null ? "" : defaultsYaml);
        } catch (InvalidConfigurationException e) {
            return new LoadResult(false, List.of("Interne Standard-Config nicht lesbar: " + e.getMessage()));
        }
        YamlConfiguration user = new YamlConfiguration();
        try {
            user.loadFromString(userYaml == null ? "" : userYaml);
        } catch (InvalidConfigurationException e) {
            return new LoadResult(false, List.of("config.yml ist kein gueltiges YAML oder nicht lesbar: " + firstLine(e.getMessage())));
        }
        int userVersion = user.getInt("config-version", 0);
        int defVersion = def.getInt("config-version", 1);
        if (userVersion > defVersion) {
            return new LoadResult(false, List.of("config-version " + userVersion + " ist neuer als von diesem Plugin unterstuetzt (" + defVersion + ")"));
        }
        ConfigMigrator.migrate(user, def);
        this.defaults = def;
        this.cfg = user;
        log.setDebug(user.getBoolean("general.debug", false));
        List<String> problems = validate(user);
        return new LoadResult(problems.isEmpty(), problems);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    // -------------------------------------------------------------- validieren

    private List<String> validate(YamlConfiguration c) {
        List<String> p = new ArrayList<>();
        range(p, c, "startup.internet-check.timeout", 500, 60000);
        range(p, c, "behavior.check-interval-minutes", 0, 100000);
        range(p, c, "behavior.check-cooldown-seconds", 0, 86400);
        range(p, c, "behavior.page-size", 1, 50);
        range(p, c, "notifications.join-delay-ticks", 0, 1200);
        range(p, c, "notifications.renotify-after-minutes", 0, 525600);
        range(p, c, "confirmations.timeout-seconds", 5, 600);
        range(p, c, "confirmations.max-pending-per-player", 1, 50);
        range(p, c, "downloads.max-size-mb", 1, 2048);
        range(p, c, "downloads.timeout-seconds", 5, 3600);
        range(p, c, "downloads.max-parallel", 1, 8);
        range(p, c, "http.timeout-seconds", 2, 120);
        range(p, c, "http.retries", 0, 10);
        range(p, c, "http.request-delay-ms", 0, 10000);
        range(p, c, "matching.retry-unmatched-hours", 0, 8760);
        range(p, c, "history.retention-days", 0, 3650);
        range(p, c, "history.max-entries", 10, 100000);
        range(p, c, "confirmations.code-length", 4, 12);
        range(p, c, "confirmations.max-attempts", 1, 10);
        if (c.contains("queue.snooze-minutes")) range(p, c, "queue.snooze-minutes", 1, 10080);
        if (c.contains("restarts.timezone")) {
            String tz = c.getString("restarts.timezone", "").trim();
            if (tz.isEmpty()) p.add("restarts.timezone: darf nicht leer sein");
            else {
                try {
                    ZoneId.of(tz);
                } catch (DateTimeException e) {
                    p.add("restarts.timezone: ungueltige Zeitzone (" + tz + ")");
                }
            }
        }
        if (c.isList("restarts.warning-seconds")) {
            for (Object item : c.getList("restarts.warning-seconds", List.of())) {
                if (!(item instanceof Number num) || num.intValue() < 1 || num.intValue() > 86400) {
                    p.add("restarts.warning-seconds: Werte muessen ganze Zahlen zwischen 1 und 86400 sein");
                    break;
                }
            }
        }
        String method = c.getString("confirmations.method", "");
        if (!method.equals("anvil") && !method.equals("chat")) {
            p.add("confirmations.method: ungueltig (erlaubt: anvil, chat)");
        }
        if (c.getString("local-restart.command", "").isBlank()) p.add("local-restart.command: darf nicht leer sein");
        if (Channel.parse(c.getString("behavior.default-channel"), null) == null) {
            p.add("behavior.default-channel: ungueltig (erlaubt: stable, beta, development)");
        }
        String mode = c.getString("notifications.mode", "");
        if (!mode.equals("once-per-update-set") && !mode.equals("once-ever-until-reset")) {
            p.add("notifications.mode: ungueltig (erlaubt: once-per-update-set, once-ever-until-reset)");
        }
        List<String> targets = c.getStringList("startup.internet-check.targets");
        if (targets.isEmpty()) p.add("startup.internet-check.targets: mindestens ein HTTPS-Ziel noetig");
        for (String t : targets) {
            try {
                URI u = URI.create(t);
                if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) p.add("startup.internet-check.targets: kein gueltiges https-Ziel: " + t);
            } catch (IllegalArgumentException e) {
                p.add("startup.internet-check.targets: ungueltige URL: " + t);
            }
        }
        ConfigurationSection plugins = c.getConfigurationSection("plugins");
        if (plugins != null) {
            for (String name : plugins.getKeys(false)) {
                ConfigurationSection m = plugins.getConfigurationSection(name);
                if (m == null) { p.add("plugins." + name + ": muss ein Abschnitt sein"); continue; }
                String src = m.getString("source", "").trim().toLowerCase(Locale.ROOT);
                if (!src.isEmpty() && !SOURCE_IDS.contains(src)) p.add("plugins." + name + ".source: unbekannt (" + src + ")");
                String sha = m.getString("sha256", "");
                if (!sha.isBlank() && !SHA256.matcher(sha.trim()).matches()) p.add("plugins." + name + ".sha256: kein gueltiger SHA-256 (64 Hex-Zeichen)");
                if (!m.getString("channel", "").isBlank() && Channel.parse(m.getString("channel"), null) == null) p.add("plugins." + name + ".channel: ungueltig");
                if (!src.isEmpty() && !src.equals("direct") && m.getString("project-id", "").isBlank()) p.add("plugins." + name + ".project-id: fehlt");
                if (src.equals("direct") && m.getString("download-url", "").isBlank()) p.add("plugins." + name + ".download-url: fehlt");
            }
        }
        return p;
    }

    private static void range(List<String> p, YamlConfiguration c, String path, int min, int max) {
        if (!c.isInt(path)) { p.add(path + ": muss eine ganze Zahl sein"); return; }
        int v = c.getInt(path);
        if (v < min || v > max) p.add(path + ": muss zwischen " + min + " und " + max + " liegen (ist " + v + ")");
    }

    // ----------------------------------------------------------------- Zugriff

    public CraftyConfig crafty() {
        YamlConfiguration c = cfg;
        String ca = c.getString("crafty.connection.ca-certificate-file", "").trim();
        Path caPath = null;
        if (!ca.isEmpty()) {
            Path candidate = Path.of(ca);
            caPath = candidate.isAbsolute() ? candidate : plugin.getDataFolder().getParentFile().getParentFile().toPath().resolve(candidate);
        }
        int timeoutMs = c.isInt("crafty.connection.timeout") ? c.getInt("crafty.connection.timeout") : -1;
        return new CraftyConfig(
                c.getBoolean("crafty.enabled", false),
                c.getString("crafty.connection.base-url", ""),
                c.getString("crafty.connection.api-base-path", ""),
                c.getString("crafty.connection.api-token", ""),
                c.getString("crafty.connection.server-id", ""),
                timeoutMs,
                c.getBoolean("crafty.connection.verify-ssl", true),
                caPath,
                c.getBoolean("crafty.connection.allow-http", false),
                c.getBoolean("crafty.restart.enabled", true));
    }

    public boolean craftyEnabled() { return cfg.getBoolean("crafty.enabled", false); }
    public String localRestartCommand() { return cfg.getString("local-restart.command", "restart"); }
    public List<String> testedVersions() { return cfg.getStringList("compat.tested-versions"); }
    public boolean historyEnabled() { return cfg.getBoolean("history.enabled", true); }
    public int historyRetentionDays() { return cfg.getInt("history.retention-days", 90); }
    public int historyMaxEntries() { return cfg.getInt("history.max-entries", 5000); }
    public boolean confirmByAnvil() { return "anvil".equals(cfg.getString("confirmations.method", "anvil")); }
    public int confirmCodeLength() { return cfg.getInt("confirmations.code-length", 6); }
    public int confirmMaxAttempts() { return cfg.getInt("confirmations.max-attempts", 3); }

    public boolean craftyRequirePending() { return cfg.getBoolean("crafty.restart.require-pending-updates", false); }
    public boolean allowReloadRecovery() { return cfg.getBoolean("startup.allow-reload-recovery", true); }
    public List<String> internetTargets() { return cfg.getStringList("startup.internet-check.targets"); }
    public int internetTimeoutMs() { return cfg.getInt("startup.internet-check.timeout", 5000); }

    public boolean checkOnStart() { return cfg.getBoolean("behavior.check-on-start", true); }
    public int checkIntervalMinutes() { return cfg.getInt("behavior.check-interval-minutes", 360); }
    public boolean allowMultipleChecks() { return cfg.getBoolean("behavior.allow-multiple-checks", false); }
    public int checkCooldownSeconds() { return cfg.getInt("behavior.check-cooldown-seconds", 60); }
    public Channel defaultChannel() { return Channel.parse(cfg.getString("behavior.default-channel"), Channel.STABLE); }
    public boolean requireMinecraftMatch() { return cfg.getBoolean("behavior.require-minecraft-match", true); }
    public boolean unknownOrderAsUpdate() { return cfg.getBoolean("behavior.treat-unknown-version-order-as-update", false); }
    public int pageSize() { return Math.max(1, cfg.getInt("behavior.page-size", 8)); }

    public boolean notificationsEnabled() { return cfg.getBoolean("notifications.enabled", true); }
    public int joinDelayTicks() { return cfg.getInt("notifications.join-delay-ticks", 40); }
    public String notificationMode() { return cfg.getString("notifications.mode", "once-per-update-set"); }
    public int renotifyMinutes() { return cfg.getInt("notifications.renotify-after-minutes", 0); }
    public boolean includePendingMatches() { return cfg.getBoolean("notifications.include-pending-matches", true); }
    public boolean titleEnabled() { return cfg.getBoolean("notifications.title.enabled", false); }
    public String titleText() { return cfg.getString("notifications.title.title", ""); }
    public String subtitleText() { return cfg.getString("notifications.title.subtitle", ""); }

    public boolean confirmationsEnabled() { return cfg.getBoolean("confirmations.enabled", true); }
    public boolean confirmationEnabledFor(String action) {
        return confirmationsEnabled() && cfg.getBoolean("confirmations.actions." + action, true);
    }
    public int confirmTtlSeconds() { return cfg.getInt("confirmations.timeout-seconds", 30); }
    public int maxPendingConfirmations() { return cfg.getInt("confirmations.max-pending-per-player", 5); }

    public long maxDownloadBytes() { return cfg.getLong("downloads.max-size-mb", 128) * 1024 * 1024; }
    public int downloadTimeoutSeconds() { return cfg.getInt("downloads.timeout-seconds", 120); }
    public int maxParallelDownloads() { return cfg.getInt("downloads.max-parallel", 2); }
    public boolean allowHttpDownloads() { return cfg.getBoolean("downloads.allow-http", false); }
    public boolean requireChecksum() { return cfg.getBoolean("downloads.require-checksum", false); }
    public boolean allOrNothing() { return cfg.getBoolean("downloads.all-or-nothing", false); }

    public String userAgent() {
        String ua = cfg.getString("http.user-agent", "");
        if (ua != null && !ua.isBlank()) return ua;
        String v = getClass().getPackage() == null ? null : getClass().getPackage().getImplementationVersion();
        return "ScamUpdates/" + (v == null ? "dev" : v);
    }
    public int httpTimeoutSeconds() { return cfg.getInt("http.timeout-seconds", 15); }
    public int httpRetries() { return cfg.getInt("http.retries", 2); }
    public int requestDelayMs() { return cfg.getInt("http.request-delay-ms", 150); }

    public boolean autoAssignSingle() { return cfg.getBoolean("matching.auto-assign-single-match", true); }
    public int retryUnmatchedHours() { return cfg.getInt("matching.retry-unmatched-hours", 24); }

    public boolean sourceEnabled(String id) { return cfg.getBoolean("sources." + id + ".enabled", true); }
    public String githubToken() { return cfg.getString("sources.github.token", ""); }

    public int queueSnoozeMinutes() { return Math.max(1, cfg.getInt("queue.snooze-minutes", 60)); }
    public boolean queueNotifyOnJoin() { return cfg.getBoolean("queue.notify-on-join", true); }

    public boolean restartsEnabled() { return cfg.getBoolean("restarts.enabled", true); }

    public ZoneId restartTimezone() {
        String raw = cfg.getString("restarts.timezone", "Europe/Berlin");
        if (raw == null || raw.isBlank()) return ZoneId.of("Europe/Berlin");
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException e) {
            return ZoneId.of("Europe/Berlin");
        }
    }

    public List<Integer> restartWarningSeconds() {
        List<Integer> raw = cfg.getIntegerList("restarts.warning-seconds");
        if (raw.isEmpty()) return List.of(300, 180, 60, 30, 10, 5, 4, 3, 2, 1);
        List<Integer> clean = new ArrayList<>();
        for (Integer s : raw) {
            if (s != null && s >= 1 && s <= 86400 && !clean.contains(s)) clean.add(s);
        }
        clean.sort(Comparator.reverseOrder());
        return clean.isEmpty() ? List.of(300, 180, 60, 30, 10, 5, 4, 3, 2, 1) : List.copyOf(clean);
    }

    public Optional<ManualMapping> manualMapping(String pluginName) {
        ConfigurationSection sec = cfg.getConfigurationSection("plugins");
        if (sec == null) return Optional.empty();
        for (String key : sec.getKeys(false)) {
            if (!key.equalsIgnoreCase(pluginName)) continue;
            ConfigurationSection m = sec.getConfigurationSection(key);
            if (m == null) return Optional.empty();
            Map<String, String> extra = new HashMap<>();
            for (String k : List.of("download-url", "sha256", "version", "artifact-regex")) {
                String v = m.getString(k, "");
                if (!v.isBlank()) extra.put(k, v.trim());
            }
            return Optional.of(new ManualMapping(key, m.getBoolean("enabled", true),
                    m.getString("source", "").trim().toLowerCase(Locale.ROOT),
                    m.getString("project-id", "").trim(), m.getString("download-url", "").trim(),
                    m.getString("sha256", "").trim().toLowerCase(Locale.ROOT),
                    Channel.parse(m.getString("channel", ""), null), extra));
        }
        return Optional.empty();
    }

    public int manualMappingCount() {
        ConfigurationSection sec = cfg.getConfigurationSection("plugins");
        return sec == null ? 0 : sec.getKeys(false).size();
    }

    // -------------------------------------------------------- Nachrichten-Rohwerte

    public Object message(String key) {
        Object v = cfg.get("messages." + key);
        return v != null ? v : defaults.get("messages." + key);
    }

    public String string(String path, String fallback) {
        String v = cfg.getString(path);
        return v != null ? v : defaults.getString(path, fallback);
    }
}
