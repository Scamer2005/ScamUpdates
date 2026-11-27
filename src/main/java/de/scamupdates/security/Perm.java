package de.scamupdates.security;

import java.util.function.Predicate;

public enum Perm {
    ADMIN("scamupdates.admin"),
    CHECK("scamupdates.check"),
    DOWNLOAD("scamupdates.download"),
    RESTORE("scamupdates.restore"),
    BACKUP("scamupdates.backup"),
    BACKUP_DELETE("scamupdates.backup.delete"),
    RESTART("scamupdates.restart"),
    CONFIG("scamupdates.config"),
    SOURCES("scamupdates.sources"),
    NOTIFY("scamupdates.notify"),
    UPDATE_PLUGINS("scamupdates.update.plugins"),
    UPDATE_SERVER("scamupdates.update.server"),
    UPDATE_SINGLE("scamupdates.update.single"),
    QUEUE("scamupdates.queue"),
    SCHEDULE("scamupdates.schedule"),
    HISTORY("scamupdates.history");

    public final String node;

    Perm(String node) { this.node = node; }

    /** Erlaubt, wenn der Knoten selbst oder scamupdates.admin gesetzt ist. */
    public boolean allows(Predicate<String> hasNode) {
        return hasNode.test(node) || hasNode.test(ADMIN.node);
    }
}
