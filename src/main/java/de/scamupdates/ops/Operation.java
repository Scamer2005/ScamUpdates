package de.scamupdates.ops;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Ein Vorgang mit eindeutiger ID und Status; unabhaengig von jeder GUI (Menues sind nur Ansichten darauf). */
public final class Operation {
    private final String id;
    private final OpType type;
    private final String key;
    private final String actor;
    private final long startedAt;
    private final Map<String, String> data = new ConcurrentHashMap<>();
    private volatile OpStatus status = OpStatus.RUNNING;
    private volatile int percent;
    private volatile String message = "";
    private volatile long finishedAt;

    Operation(String id, OpType type, String key, String actor, long startedAt) {
        this.id = id;
        this.type = type;
        this.key = key;
        this.actor = actor;
        this.startedAt = startedAt;
    }

    public String id() { return id; }
    public OpType type() { return type; }
    public String key() { return key; }
    public String actor() { return actor; }
    public long startedAt() { return startedAt; }
    public long finishedAt() { return finishedAt; }
    public OpStatus status() { return status; }
    public int percent() { return percent; }
    public String message() { return message; }
    public Map<String, String> data() { return data; }
    public boolean isActive() { return status.isActive(); }

    void set(OpStatus status, int percent, String message, long finishedAt) {
        this.status = status;
        this.percent = percent;
        this.message = message == null ? "" : message;
        this.finishedAt = finishedAt;
    }

    void progress(int percent, String message) {
        this.percent = Math.max(0, Math.min(100, percent));
        if (message != null) this.message = message;
    }
}
