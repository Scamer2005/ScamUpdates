package de.scamupdates.restart;

import de.scamupdates.backup.BackupManager;
import de.scamupdates.config.ConfigManager;
import de.scamupdates.crafty.CraftyClient;
import de.scamupdates.history.HistoryEntry;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.security.OperationLock;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.JarValidator;
import de.scamupdates.update.TransactionStore;
import de.scamupdates.utils.AtomicFiles;
import de.scamupdates.utils.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Neustart ueber Crafty-API oder lokalen /restart nach Vorpruefung. Es wird kein Erfolg vorgetaeuscht. */
public final class RestartService {

    public enum Result { REQUESTED, REQUESTED_LOCAL, PREFLIGHT_FAILED, DISABLED, NO_PENDING, FAILED, BUSY }

    public enum RestartMode { CRAFTY, LOCAL, UNAVAILABLE }

    public record Readiness(boolean ready, RestartMode mode, String detail) {}

    public record Outcome(Result result, List<String> details) {}

    private final StateManager state;
    private final ConfigManager config;
    private final ScamLog log;
    private final OperationLock lock;
    private final ExecutorService io;
    private final TransactionStore transactions;
    private final BackupManager backups;
    private final Path updateDir;
    private final Supplier<CraftyClient> crafty;
    private final Supplier<String> mcVersion;
    private volatile LocalRestart local;
    private volatile Consumer<Runnable> mainRunner;
    private volatile HistoryStore history;

    public RestartService(StateManager state, ConfigManager config, ScamLog log, OperationLock lock, ExecutorService io,
                          TransactionStore transactions, BackupManager backups, Path updateDir,
                          Supplier<CraftyClient> crafty, Supplier<String> mcVersion) {
        this.state = state;
        this.config = config;
        this.log = log;
        this.lock = lock;
        this.io = io;
        this.transactions = transactions;
        this.backups = backups;
        this.updateDir = updateDir;
        this.crafty = crafty;
        this.mcVersion = mcVersion;
    }

    /** Lokaler Neustart (/restart) fuer crafty.enabled: false; mainRunner fuehrt Code auf dem Hauptthread aus. */
    public void setLocal(LocalRestart local, Consumer<Runnable> mainRunner) {
        this.local = local;
        this.mainRunner = mainRunner;
    }

    public void setHistory(HistoryStore history) { this.history = history; }

    private void record(String result, String actor, String msg) {
        HistoryStore h = history;
        if (h != null) h.append(HistoryEntry.of("restart", result, actor, msg));
    }

    /** Prueft, welcher Neustart-Weg aktuell einsatzbereit ist (Grundlage fuer manuelle und geplante Neustarts). */
    public Readiness readiness() {
        if (config != null && config.crafty().enabled()) {
            if (!config.crafty().restartEnabled()) {
                return new Readiness(false, RestartMode.UNAVAILABLE, "Crafty-Neustart in config.yml deaktiviert");
            }
            CraftyClient c = crafty.get();
            return c == null
                    ? new Readiness(false, RestartMode.UNAVAILABLE, "Crafty-Integration deaktiviert oder nicht verbunden")
                    : new Readiness(true, RestartMode.CRAFTY, "Crafty Controller");
        }
        LocalRestart lr = local;
        if (lr == null || mainRunner == null) {
            return new Readiness(false, RestartMode.UNAVAILABLE, "Lokaler Neustart nicht konfiguriert");
        }
        return new Readiness(true, RestartMode.LOCAL, "Lokaler /" + (config == null ? "restart" : config.localRestartCommand()));
    }

    public CompletableFuture<Outcome> restart(String initiator) {
        return restart(initiator, config != null && config.craftyRequirePending());
    }

    /** Startet einen Neustart mit expliziter Pruefung auf vorgemerkte Updates (fuer direkte und geplante Neustarts). */
    public CompletableFuture<Outcome> restart(String initiator, boolean requirePending) {
        CompletableFuture<Outcome> f = new CompletableFuture<>();
        try {
            state.requireActive();
        } catch (IllegalStateException e) {
            f.completeExceptionally(e);
            return f;
        }
        io.submit(() -> {
            try {
                f.complete(run(initiator, requirePending));
            } catch (Throwable t) {
                log.error("Neustart-Vorgang fehlgeschlagen", t);
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    Outcome run(String initiator) {
        return run(initiator, config != null && config.craftyRequirePending());
    }

    Outcome run(String initiator, boolean requirePending) {
        boolean useCrafty = config.crafty().enabled();
        if (useCrafty && !config.crafty().restartEnabled()) return new Outcome(Result.DISABLED, List.of());
        Optional<OperationLock.Handle> handle = lock.tryAcquire("restart");
        if (handle.isEmpty()) return new Outcome(Result.BUSY, List.of(lock.holder()));
        try (OperationLock.Handle h = handle.get()) {
            state.requireActive();
            List<TransactionStore.Pending> pending = transactions.all();
            if (pending.isEmpty() && requirePending) return new Outcome(Result.NO_PENDING, List.of());
            List<String> problems = preflight(pending);
            if (!problems.isEmpty()) {
                log.warn("Neustart abgebrochen, Vorpruefung fehlgeschlagen: " + String.join("; ", problems));
                return new Outcome(Result.PREFLIGHT_FAILED, problems);
            }
            if (!useCrafty) return runLocal(initiator, pending.size());
            CraftyClient client = crafty.get();
            if (client == null) {
                log.warn("Crafty-Neustart angefordert von " + initiator + ", aber Crafty-Integration ist deaktiviert/nicht verbunden.");
                return new Outcome(Result.FAILED, List.of("Crafty-Integration ist deaktiviert oder nicht erreichbar"));
            }
            log.info("Neustart ueber Crafty angefordert von " + initiator + " (" + pending.size() + " vorgemerkte Aenderung(en)).");
            CraftyClient.Result r = client.restartServer();
            if (r.ok()) {
                log.info("Crafty hat den Neustart angenommen (HTTP " + r.httpStatus() + ").");
                record("OK", initiator, "Neustart ueber Crafty angefordert (" + pending.size() + " vorgemerkte Aenderung(en))");
                return new Outcome(Result.REQUESTED, List.of());
            }
            log.error("Crafty-Neustart fehlgeschlagen: " + r.status() + " - " + r.message(), null);
            record("FAILED", initiator, "Crafty-Neustart fehlgeschlagen: " + r.status());
            return new Outcome(Result.FAILED, List.of(r.message()));
        }
    }

    private Outcome runLocal(String initiator, int pendingCount) {
        LocalRestart lr = local;
        Consumer<Runnable> main = mainRunner;
        if (lr == null || main == null) return new Outcome(Result.FAILED, List.of("Lokaler Neustart nicht verfuegbar"));
        CompletableFuture<LocalRestart.Status> done = new CompletableFuture<>();
        log.info("Lokaler Neustart (/restart) angefordert von " + initiator + " (" + pendingCount + " vorgemerkte Aenderung(en)).");
        main.accept(() -> {
            try {
                done.complete(lr.trigger());
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        try {
            LocalRestart.Status st = done.get(15, TimeUnit.SECONDS);
            if (st.ok()) {
                record("OK", initiator, "Lokaler Neustart per /restart ausgeloest (" + pendingCount + " vorgemerkte Aenderung(en))");
                return new Outcome(Result.REQUESTED_LOCAL, List.of());
            }
            log.warn("Lokaler Neustart nicht moeglich: " + st.detail());
            record("FAILED", initiator, "Lokaler Neustart nicht moeglich: " + st.detail());
            return new Outcome(Result.FAILED, List.of(st.detail()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(Result.FAILED, List.of("unterbrochen"));
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            log.error("Lokaler Neustart fehlgeschlagen", e);
            return new Outcome(Result.FAILED, List.of("Lokaler Neustart fehlgeschlagen: " + e.getClass().getSimpleName()));
        }
    }

    /** Pruefpunkte 2-4: Dateien vollstaendig, validiert, Backups vorhanden (5 = Lock, 1 = Bestaetigung vorher). */
    List<String> preflight(List<TransactionStore.Pending> pending) {
        List<String> problems = new ArrayList<>();
        for (TransactionStore.Pending p : pending) {
            try {
                Path staged = AtomicFiles.requireInside(updateDir, p.stagedFile());
                if (!Files.isRegularFile(staged)) { problems.add(p.plugin() + ": Datei in plugins/update fehlt"); continue; }
                if (!Hashing.sha256(staged).equalsIgnoreCase(p.stagedSha256())) { problems.add(p.plugin() + ": Datei wurde veraendert"); continue; }
                JarValidator.validate(staged, p.plugin(), mcVersion.get());
                Optional<BackupManager.Entry> be = backups.get(p.plugin());
                if (be.isEmpty() || !backups.verify(be.get())) problems.add(p.plugin() + ": Backup fehlt oder ist beschaedigt");
            } catch (JarValidator.ValidationException e) {
                problems.add(p.plugin() + ": " + e.getMessage());
            } catch (IOException e) {
                problems.add(p.plugin() + ": " + e.getMessage());
            }
        }
        return problems;
    }
}
