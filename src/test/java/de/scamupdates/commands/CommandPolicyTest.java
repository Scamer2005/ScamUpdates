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
        assertEquals(Perm.QUEUE, req("queue"));
        assertEquals(Perm.QUEUE, req("queue", "later", "Foo"));
        assertEquals(Perm.UPDATE_SINGLE, req("single", "update", "Foo"));
        assertEquals(Perm.SCHEDULE, req("schedule", "onetime", "5m"));
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
        for (String blocked : new String[]{"check", "download", "restore", "backup", "restart", "list",
                "sources", "exclude", "include", "match", "pick", "queue", "single", "schedule", "confirm"}) {
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
        assertFalse(Perm.UPDATE_SINGLE.allows(n -> n.equals("scamupdates.check")));
        assertFalse(Perm.QUEUE.allows(n -> n.equals("scamupdates.check")));
        assertFalse(Perm.SCHEDULE.allows(n -> n.equals("scamupdates.restart")));
        assertFalse(Perm.DOWNLOAD.allows(n -> false));
    }

    @Test
    void onlyOnlineCommandsRequireInternet() {
        assertTrue(CommandPolicy.requiresOnline("check"));
        assertTrue(CommandPolicy.requiresOnline("download"));
        for (String local : new String[]{"help", "status", "list", "restore", "backup", "restart",
                "config", "sources", "exclude", "include", "match", "pick", "history",
                "queue", "single", "schedule", "confirm", "cancel", "later"}) {
            assertFalse(CommandPolicy.requiresOnline(local), local + " muss auch offline verfuegbar bleiben");
        }
        assertFalse(CommandPolicy.requiresOnline((String) null));
    }

    @Test
    void subactionAwareOnlineChecksDistinguishOfflineQueueAndSingleFromNetworkActions() {
        assertTrue(CommandPolicy.requiresOnline(new String[]{"check"}));
        assertTrue(CommandPolicy.requiresOnline(new String[]{"download"}));
        assertTrue(CommandPolicy.requiresOnline(new String[]{"single", "check", "Vault"}));
        assertTrue(CommandPolicy.requiresOnline(new String[]{"single", "update", "Vault"}));
        assertTrue(CommandPolicy.requiresOnline(new String[]{"queue", "check"}));
        assertTrue(CommandPolicy.requiresOnline(new String[]{"queue", "download"}));
        assertTrue(CommandPolicy.requiresOnline(new String[]{"queue", "update", "Vault"}));

        assertFalse(CommandPolicy.requiresOnline(new String[]{"queue"}));
        assertFalse(CommandPolicy.requiresOnline(new String[]{"queue", "list"}));
        assertFalse(CommandPolicy.requiresOnline(new String[]{"queue", "add", "Vault"}));
        assertFalse(CommandPolicy.requiresOnline(new String[]{"queue", "remove", "Vault"}));
        assertFalse(CommandPolicy.requiresOnline(new String[]{"queue", "later", "Vault"}));
        assertFalse(CommandPolicy.requiresOnline(new String[]{"queue", "dismiss", "Vault"}));
        assertFalse(CommandPolicy.requiresOnline(new String[]{"single"}));
        assertFalse(CommandPolicy.requiresOnline(new String[0]));
        assertFalse(CommandPolicy.requiresOnline((String[]) null));
    }
}
