package de.scamupdates.commands;

import de.scamupdates.security.Perm;

import java.util.Locale;
import java.util.Set;

/** Zentrale Zuordnung Befehl -> benoetigte Berechtigung (serverseitig fuer Befehle UND Chat-Klicks, da Klicks nur Befehle sind). */
public final class CommandPolicy {
    private CommandPolicy() {}

    /** Befehle, die auch im deaktivierten Fehlerzustand erlaubt sind (keine Update-Aktion). */
    public static final Set<String> ALLOWED_WHEN_DISABLED = Set.of("help", "status", "config", "cancel", "later");

    /** Befehle, die eine Internetverbindung benoetigen (werden bei ACTIVE_OFFLINE eingeschraenkt). */
    public static final Set<String> ONLINE_REQUIRED = Set.of("check", "download");
    public static final Set<String> ONLINE_SINGLE_SUBACTIONS = Set.of("check", "update");
    public static final Set<String> ONLINE_QUEUE_SUBACTIONS = Set.of("check", "download", "update");

    public static final Set<String> SUBCOMMANDS = Set.of("help", "check", "download", "status", "list", "restore", "backup",
            "restart", "config", "sources", "exclude", "include", "match", "pick", "history", "confirm", "cancel", "later",
            "queue", "single", "schedule");

    public static boolean requiresOnline(String subcommand) {
        return subcommand != null && ONLINE_REQUIRED.contains(subcommand.toLowerCase(Locale.ROOT));
    }

    public static boolean requiresOnline(String[] args) {
        if (args == null || args.length == 0 || args[0] == null) return false;
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (ONLINE_REQUIRED.contains(sub)) return true;
        if (args.length < 2 || args[1] == null) return false;
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "single" -> ONLINE_SINGLE_SUBACTIONS.contains(action);
            case "queue" -> ONLINE_QUEUE_SUBACTIONS.contains(action);
            default -> false;
        };
    }

    /** @return benoetigte Berechtigung oder null, wenn der Befehl selbst keine braucht (confirm/cancel/later). */
    public static Perm required(String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "help", "check", "status", "list", "match" -> Perm.CHECK;
            case "download" -> Perm.DOWNLOAD;
            case "restore" -> Perm.RESTORE;
            case "backup" -> args.length >= 2 && args[1].equalsIgnoreCase("delete") ? Perm.BACKUP_DELETE : Perm.BACKUP;
            case "restart" -> Perm.RESTART;
            case "config" -> Perm.CONFIG;
            case "sources", "exclude", "include", "pick" -> Perm.SOURCES;
            case "history" -> Perm.HISTORY;
            case "queue" -> Perm.QUEUE;
            case "single" -> Perm.UPDATE_SINGLE;
            case "schedule" -> Perm.SCHEDULE;
            default -> null;
        };
    }
}
