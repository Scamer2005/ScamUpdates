package de.scamupdates.ops;

public enum OpStatus {
    RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED;

    public boolean isActive() { return this == RUNNING; }
}
