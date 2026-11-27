package de.scamupdates.rollback;

import de.scamupdates.backup.BackupManager;
import de.scamupdates.history.HistoryEntry;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.scan.PluginJarIndex;
import de.scamupdates.security.OperationLock;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.JarValidator;
import de.scamupdates.update.Stager;
import de.scamupdates.update.TransactionStore;
import de.scamupdates.utils.AtomicFiles;
import de.scamupdates.utils.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Rollback: stellt die gesicherte JAR ueber plugins/update/ wieder her; die aktuelle JAR wird dabei zum neuen Backup. */
public final class RollbackService {

    public enum Problem { NO_BACKUP, PENDING, NOT_INSTALLED, BACKUP_CORRUPT, IDENTICAL }

    public record Plan(BackupManager.Entry backup, PluginInfo installed) {}

    public record Failure(String plugin, Problem problem) {}

    public record PlanResult(List<Plan> plans, List<Failure> failures) {}

    public record Item(String plugin, String from, String to, boolean ok, String error) {}

    private final StateManager state;
    private final BackupManager backups;
    private final Stager stager;
    private final TransactionStore transactions;
    private final ScamLog log;
    private final OperationLock lock;
    private final ExecutorService io;
    private final Path pluginsDir;
    private final Path tmpDir;
    private final Supplier<String> mcVersion;
    private volatile HistoryStore history;

    public RollbackService(StateManager state, BackupManager backups, Stager stager, TransactionStore transactions,
                           ScamLog log, OperationLock lock, ExecutorService io, Path pluginsDir, Path tmpDir,
                           Supplier<String> mcVersion) {
        this.state = state;
        this.backups = backups;
        this.stager = stager;
        this.transactions = transactions;
        this.log = log;
        this.lock = lock;
        this.io = io;
        this.pluginsDir = pluginsDir;
        this.tmpDir = tmpDir;
        this.mcVersion = mcVersion;
    }

    public void setHistory(HistoryStore history) { this.history = history; }

    /** Blockierend (Dateizugriffe). names leer = alle vorhandenen Backups. */
    public PlanResult plan(List<PluginInfo> metas, List<String> names) throws IOException {
        state.requireActive();
        List<PluginInfo> plugins = PluginJarIndex.attach(metas, pluginsDir);
        List<String> targets = new ArrayList<>(names);
        if (targets.isEmpty()) for (BackupManager.Entry e : backups.list()) targets.add(e.plugin());
        List<Plan> plans = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (String name : targets) {
            Optional<BackupManager.Entry> be = backups.get(name);
            if (be.isEmpty()) { failures.add(new Failure(name, Problem.NO_BACKUP)); continue; }
            BackupManager.Entry entry = be.get();
            if (transactions.get(entry.plugin()).isPresent()) { failures.add(new Failure(entry.plugin(), Problem.PENDING)); continue; }
            PluginInfo inst = plugins.stream().filter(p -> p.name().equalsIgnoreCase(entry.plugin())).findFirst().orElse(null);
            if (inst == null || inst.jarPath() == null) { failures.add(new Failure(entry.plugin(), Problem.NOT_INSTALLED)); continue; }
            if (!backups.verify(entry)) { failures.add(new Failure(entry.plugin(), Problem.BACKUP_CORRUPT)); continue; }
            if (Hashing.sha256(inst.jarPath()).equals(entry.sha256())) { failures.add(new Failure(entry.plugin(), Problem.IDENTICAL)); continue; }
            plans.add(new Plan(entry, inst));
        }
        return new PlanResult(plans, failures);
    }

    public CompletableFuture<PlanResult> planAsync(List<PluginInfo> metas, List<String> names) {
        CompletableFuture<PlanResult> f = new CompletableFuture<>();
        io.submit(() -> {
            try {
                f.complete(plan(metas, names));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    public CompletableFuture<List<Item>> executeAll(List<Plan> plans, Consumer<Item> progress) {
        return executeAll(plans, progress, "system");
    }

    public CompletableFuture<List<Item>> executeAll(List<Plan> plans, Consumer<Item> progress, String actor) {
        CompletableFuture<List<Item>> f = new CompletableFuture<>();
        try {
            state.requireActive();
        } catch (IllegalStateException e) {
            f.completeExceptionally(e);
            return f;
        }
        Optional<OperationLock.Handle> handle = lock.tryAcquire("restore");
        if (handle.isEmpty()) {
            f.completeExceptionally(new de.scamupdates.update.UpdateService.BusyException(lock.holder()));
            return f;
        }
        try {
            io.submit(() -> {
                try (OperationLock.Handle h = handle.get()) {
                    List<Item> items = new ArrayList<>();
                    for (Plan p : plans) {
                        state.requireActive();
                        Item it = execute(p);
                        items.add(it);
                        progress.accept(it);
                    }
                    HistoryStore h0 = history;
                    if (h0 != null) {
                        long ok = items.stream().filter(Item::ok).count();
                        List<HistoryEntry.Detail> details = new ArrayList<>();
                        for (Item it : items) details.add(new HistoryEntry.Detail(it.plugin(), it.from(), it.to(), it.ok() ? "OK" : "FEHLER: " + it.error()));
                        h0.append(HistoryEntry.of("restore", ok == 0 ? "FAILED" : (ok < items.size() ? "WARN" : "OK"), actor,
                                ok + " von " + items.size() + " Rollback(s) vorgemerkt").withDetails(details));
                    }
                    f.complete(items);
                } catch (Throwable t) {
                    log.error("Rollback fehlgeschlagen", t);
                    f.completeExceptionally(t);
                }
            });
        } catch (RuntimeException e) {
            handle.get().close();
            f.completeExceptionally(e);
        }
        return f;
    }

    /** Fuehrt ein einzelnes Rollback aus (blockierend; Aufrufer haelt die OperationLock). */
    public Item execute(Plan plan) {
        BackupManager.Entry entry = plan.backup();
        PluginInfo inst = plan.installed();
        String from = inst.version(), to = entry.version();
        Path tmp = tmpDir.resolve(UUID.randomUUID() + ".part");
        try {
            if (transactions.get(entry.plugin()).isPresent()) return fail(entry, from, "bereits eine vorgemerkte Aenderung vorhanden");
            if (!backups.verify(entry)) return fail(entry, from, "Backup fehlt oder wurde veraendert");
            Files.createDirectories(tmpDir);
            Files.copy(backups.fileOf(entry), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            JarValidator.validate(tmp, entry.plugin(), mcVersion.get());
            Stager.Result r = stager.stage(entry.plugin(), inst.version(), inst.jarPath(), tmp);
            transactions.put(new TransactionStore.Pending(entry.plugin(), TransactionStore.Kind.RESTORE, from, to,
                    r.stagedFile().getFileName().toString(), r.sha256(), System.currentTimeMillis()));
            log.info("Rollback vorgemerkt: " + entry.plugin() + " " + from + " -> " + to
                    + " (bisherige Version " + from + " ist jetzt das neue Backup)");
            return new Item(entry.plugin(), from, to, true, null);
        } catch (JarValidator.ValidationException e) {
            return fail(entry, from, "Backup-JAR ungueltig: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.error("Rollback fuer " + entry.plugin() + " fehlgeschlagen", e);
            return fail(entry, from, log.redact(String.valueOf(e.getMessage())));
        } finally {
            AtomicFiles.deleteQuietly(tmp);
        }
    }

    private Item fail(BackupManager.Entry e, String from, String msg) {
        log.warn("Rollback fehlgeschlagen fuer " + e.plugin() + ": " + msg);
        return new Item(e.plugin(), from, e.version(), false, msg);
    }
}
