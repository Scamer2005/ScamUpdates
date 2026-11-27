package de.scamupdates.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmationManagerTest {
    final AtomicLong now = new AtomicLong(1_000_000);
    final ConfirmationManager mgr = new ConfirmationManager(now::get, () -> 30, () -> 2);
    final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();

    @Test
    void ownerCanConfirmOnceAndRunnableIsReturned() {
        AtomicInteger runs = new AtomicInteger();
        var p = mgr.create(alice, "download", Perm.DOWNLOAD, runs::incrementAndGet);
        var out = mgr.confirm(p.token(), alice, perm -> true);
        assertEquals(ConfirmationManager.ConfirmResult.OK, out.result());
        out.pending().run().run();
        assertEquals(1, runs.get());
        // Doppelklick: Token ist verbraucht
        assertEquals(ConfirmationManager.ConfirmResult.INVALID, mgr.confirm(p.token(), alice, perm -> true).result());
    }

    @Test
    void otherPlayersCannotTakeOverAndTokenStaysValidForOwner() {
        var p = mgr.create(alice, "download", Perm.DOWNLOAD, () -> {});
        assertEquals(ConfirmationManager.ConfirmResult.NOT_OWNER, mgr.confirm(p.token(), bob, perm -> true).result());
        assertEquals(ConfirmationManager.ConfirmResult.OK, mgr.confirm(p.token(), alice, perm -> true).result());
    }

    @Test
    void expiredTokensDoNotWork() {
        var p = mgr.create(alice, "restart", Perm.RESTART, () -> {});
        now.addAndGet(31_000);
        assertEquals(ConfirmationManager.ConfirmResult.EXPIRED, mgr.confirm(p.token(), alice, perm -> true).result());
        assertEquals(ConfirmationManager.ConfirmResult.INVALID, mgr.confirm(p.token(), alice, perm -> true).result());
    }

    @Test
    void permissionIsCheckedAgainAtConfirmTime() {
        var p = mgr.create(alice, "restart", Perm.RESTART, () -> {});
        var out = mgr.confirm(p.token(), alice, perm -> perm != Perm.RESTART);
        assertEquals(ConfirmationManager.ConfirmResult.NO_PERMISSION, out.result());
        assertNull(out.pending());
        assertEquals(ConfirmationManager.ConfirmResult.INVALID, mgr.confirm(p.token(), alice, perm -> true).result());
    }

    @Test
    void sameActionReplacesOlderRequestAndLimitIsEnforced() {
        var first = mgr.create(alice, "download", Perm.DOWNLOAD, () -> {});
        var second = mgr.create(alice, "download", Perm.DOWNLOAD, () -> {});
        assertEquals(ConfirmationManager.ConfirmResult.INVALID, mgr.confirm(first.token(), alice, perm -> true).result());
        mgr.create(alice, "restart", Perm.RESTART, () -> {});
        assertThrows(ConfirmationManager.TooManyPendingException.class, () -> mgr.create(alice, "check", Perm.CHECK, () -> {}));
        assertEquals(ConfirmationManager.ConfirmResult.OK, mgr.confirm(second.token(), alice, perm -> true).result());
    }

    @Test
    void cancelOnlyAffectsOwnTokens() {
        var a = mgr.create(alice, "download", Perm.DOWNLOAD, () -> {});
        mgr.create(bob, "download", Perm.DOWNLOAD, () -> {});
        assertFalse(mgr.cancel(a.token(), bob));
        assertEquals(1, mgr.cancelAll(alice));
        assertEquals(1, mgr.size());
    }

    @Test
    void purgeRemovesExpired() {
        mgr.create(alice, "download", Perm.DOWNLOAD, () -> {});
        now.addAndGet(60_000);
        mgr.purgeExpired();
        assertEquals(0, mgr.size());
    }

    @Test
    void codeLengthIsConfigurableAndClamped() {
        ConfirmationManager six = new ConfirmationManager(now::get, () -> 30, () -> 5, () -> 6);
        assertEquals(6, six.create(alice, "download", Perm.DOWNLOAD, () -> {}).token().length());
        ConfirmationManager tiny = new ConfirmationManager(now::get, () -> 30, () -> 5, () -> 1);
        assertEquals(4, tiny.create(alice, "download", Perm.DOWNLOAD, () -> {}).token().length());
        ConfirmationManager huge = new ConfirmationManager(now::get, () -> 30, () -> 5, () -> 99);
        assertEquals(12, huge.create(alice, "download", Perm.DOWNLOAD, () -> {}).token().length());
    }

    @Test
    void codeIsCaseInsensitiveWhenEntered() {
        var p = mgr.create(alice, "download", Perm.DOWNLOAD, () -> {});
        assertEquals(ConfirmationManager.ConfirmResult.OK, mgr.confirm(p.token().toLowerCase(), alice, perm -> true).result());
    }

    @Test
    void operationLockPreventsParallelOperations() {
        OperationLock lock = new OperationLock();
        var h = lock.tryAcquire("download");
        assertTrue(h.isPresent());
        assertTrue(lock.tryAcquire("restore").isEmpty());
        assertEquals("download", lock.holder());
        h.get().close();
        assertTrue(lock.tryAcquire("restore").isPresent());
    }
}
