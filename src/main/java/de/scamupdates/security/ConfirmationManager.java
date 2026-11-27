package de.scamupdates.security;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Zeitlich begrenzte, einmalige, an den ausfuehrenden Spieler gebundene Bestaetigungstokens. */
public final class ConfirmationManager {

    public enum ConfirmResult { OK, INVALID, NOT_OWNER, EXPIRED, NO_PERMISSION }

    public record Pending(String token, UUID owner, String action, Perm perm, long expiresAtMillis, Runnable run) {}

    public record Outcome(ConfirmResult result, Pending pending) {}

    public static final class TooManyPendingException extends RuntimeException {
        public TooManyPendingException() { super("Zu viele offene Bestaetigungen"); }
    }

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private final IntSupplier ttlSeconds;
    private final IntSupplier maxPerOwner;
    private final IntSupplier codeLength;

    public ConfirmationManager(LongSupplier clock, IntSupplier ttlSeconds, IntSupplier maxPerOwner, IntSupplier codeLength) {
        this.clock = clock;
        this.ttlSeconds = ttlSeconds;
        this.maxPerOwner = maxPerOwner;
        this.codeLength = codeLength;
    }

    public ConfirmationManager(LongSupplier clock, IntSupplier ttlSeconds, IntSupplier maxPerOwner) {
        this(clock, ttlSeconds, maxPerOwner, () -> 10);
    }

    public ConfirmationManager(IntSupplier ttlSeconds, IntSupplier maxPerOwner) {
        this(System::currentTimeMillis, ttlSeconds, maxPerOwner);
    }

    public Pending create(UUID owner, String action, Perm perm, Runnable run) {
        purgeExpired();
        // Gleiche Aktion desselben Spielers ersetzt die aeltere Anfrage (kein Token-Spam).
        pending.values().removeIf(p -> p.owner().equals(owner) && p.action().equals(action));
        long count = pending.values().stream().filter(p -> p.owner().equals(owner)).count();
        if (count >= Math.max(1, maxPerOwner.getAsInt())) throw new TooManyPendingException();
        String token = newToken();
        Pending p = new Pending(token, owner, action, perm, clock.getAsLong() + ttlSeconds.getAsInt() * 1000L, run);
        pending.put(token, p);
        return p;
    }

    /** Verbraucht das Token atomar (Doppelklick -> zweiter Aufruf liefert INVALID). */
    public Outcome confirm(String token, UUID who, Predicate<Perm> hasPerm) {
        Pending p = token == null ? null : pending.get(token.toUpperCase());
        if (p == null) return new Outcome(ConfirmResult.INVALID, null);
        if (!p.owner().equals(who)) return new Outcome(ConfirmResult.NOT_OWNER, null);
        if (clock.getAsLong() > p.expiresAtMillis()) {
            pending.remove(p.token(), p);
            return new Outcome(ConfirmResult.EXPIRED, null);
        }
        if (!hasPerm.test(p.perm())) {
            pending.remove(p.token(), p);
            return new Outcome(ConfirmResult.NO_PERMISSION, null);
        }
        if (!pending.remove(p.token(), p)) return new Outcome(ConfirmResult.INVALID, null);
        return new Outcome(ConfirmResult.OK, p);
    }

    public int cancelAll(UUID owner) {
        List<String> remove = new ArrayList<>();
        pending.forEach((t, p) -> { if (p.owner().equals(owner)) remove.add(t); });
        remove.forEach(pending::remove);
        return remove.size();
    }

    public boolean cancel(String token, UUID owner) {
        Pending p = token == null ? null : pending.get(token.toUpperCase());
        return p != null && p.owner().equals(owner) && pending.remove(p.token(), p);
    }

    public void purgeExpired() {
        long now = clock.getAsLong();
        pending.values().removeIf(p -> now > p.expiresAtMillis());
    }

    public void clear() { pending.clear(); }

    public int size() { return pending.size(); }

    private String newToken() {
        String t;
        do {
            int len = Math.max(4, Math.min(12, codeLength.getAsInt()));
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            t = sb.toString();
        } while (pending.containsKey(t));
        return t;
    }
}
