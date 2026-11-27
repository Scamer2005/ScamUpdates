package de.scamupdates.commands;

import de.scamupdates.security.Perm;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CommandPolicyTest {

    private static Perm req(String... args) { return CommandPolicy.required(args); }

    @Test
    void criticalCommandsRequireTheirOwnPermission() {
        assertEquals(Perm.DOWNLOAD, req("download"));
        assertEquals(Perm.RESTORE, req("restore", "Foo"));
        assertEquals(Perm.RESTORE, req("restore", "all"));
        assertEquals(Perm.BACKUP, req("backup", "list"));
        assertEquals(Perm.BACKUP_DELETE, req("backup", "delete", "Foo"));
        assertEquals(Perm.BACKUP_DELETE, req("backup", "delete", "all"));
        assertEquals(Perm.RESTART, req("restart"));
        assertEquals(Perm.CONFIG, req("config", "reload"));
        assertEquals(Perm.SOURCES, req("sources"));
        assertEquals(Perm.SOURCES, req("exclude", "Foo"));
        assertEquals(Perm.SOURCES, req("include", "Foo"));
        assertEquals(Perm.CHECK, req("check"));
        assertEquals(Perm.CHECK, req("status"));
        assertEquals(Perm.CHECK, req());
        assertEquals(Perm.HISTORY, req("history"));
    }

    @Test
    void confirmCancelLaterNeedNoPermissionThemselves() {
        assertNull(req("confirm", "ABC"));
        assertNull(req("cancel"));
        assertNull(req("later"));
    }

    @Test
    void everySubcommandIsKnownToThePolicy() {
        for (String sub : CommandPolicy.SUBCOMMANDS) {
            Perm p = req(sub);
            if (Set.of("confirm", "cancel", "later").contains(sub)) assertNull(p, sub);
            else assertNotNull(p, sub);
        }
    }

    @Test
    void onlyHarmlessCommandsWorkWhileDisabled() {
        for (String blocked : new String[]{"check", "download", "restore", "backup", "restart", "list", "sources", "exclude", "include", "match", "pick", "confirm"}) {
            assertFalse(CommandPolicy.ALLOWED_WHEN_DISABLED.contains(blocked), blocked);
        }
        assertTrue(CommandPolicy.ALLOWED_WHEN_DISABLED.contains("status"));
        assertTrue(CommandPolicy.ALLOWED_WHEN_DISABLED.contains("config"));
    }

    @Test
    void adminImpliesAllButSpecificNodesDoNot() {
        assertTrue(Perm.RESTART.allows(n -> n.equals("scamupdates.admin")));
        assertTrue(Perm.RESTART.allows(n -> n.equals("scamupdates.restart")));
        assertFalse(Perm.RESTART.allows(n -> n.equals("scamupdates.download")));
        assertFalse(Perm.BACKUP_DELETE.allows(n -> n.equals("scamupdates.backup")));
        assertFalse(Perm.DOWNLOAD.allows(n -> false));
    }

    @Test
    void onlyOnlineCommandsRequireInternet() {
        assertTrue(CommandPolicy.requiresOnline("check"));
        assertTrue(CommandPolicy.requiresOnline("download"));
        for (String local : new String[]{"help", "status", "list", "restore", "backup", "restart",
                "config", "sources", "exclude", "include", "match", "pick", "history", "confirm", "cancel", "later"}) {
            assertFalse(CommandPolicy.requiresOnline(local), local + " muss auch offline verfuegbar bleiben");
        }
        assertFalse(CommandPolicy.requiresOnline(null));
    }
}
