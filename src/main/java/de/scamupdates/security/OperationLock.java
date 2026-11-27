package de.scamupdates.security;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Globale Sperre: es laeuft hoechstens eine Update-/Rollback-/Restart-/Loesch-Operation gleichzeitig. */
public final class OperationLock {

    public final class Handle implements AutoCloseable {
        private final String name;
        private Handle(String name) { this.name = name; }
        @Override public void close() { holder.compareAndSet(name, null); }
    }

    private final AtomicReference<String> holder = new AtomicReference<>();

    public Optional<Handle> tryAcquire(String name) {
        return holder.compareAndSet(null, name) ? Optional.of(new Handle(name)) : Optional.empty();
    }

    public boolean isBusy() { return holder.get() != null; }

    public String holder() { return holder.get(); }
}
