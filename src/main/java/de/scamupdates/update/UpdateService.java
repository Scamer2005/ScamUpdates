package de.scamupdates.update;

import de.scamupdates.ScamUpdates;
import de.scamupdates.config.ConfigManager;
import de.scamupdates.config.ManualMapping;
import de.scamupdates.history.HistoryEntry;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.net.PaperDownloads;
import de.scamupdates.ops.OpStatus;
import de.scamupdates.ops.OpType;
import de.scamupdates.ops.Operation;
import de.scamupdates.ops.OperationRegistry;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.match.MatchService;
import de.scamupdates.model.Channel;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.model.RemoteRelease;
import de.scamupdates.model.UpdateCandidate;
import de.scamupdates.net.ConnectivityGuard;
import de.scamupdates.net.HttpService;
import de.scamupdates.queue.UpdateQueueStore;
import de.scamupdates.scan.PluginJarIndex;
import de.scamupdates.scan.PluginScanner;
import de.scamupdates.security.OperationLock;
import de.scamupdates.sources.McVersionMatcher;
import de.scamupdates.sources.ReleaseQuery;
import de.scamupdates.sources.SourceRegistry;
import de.scamupdates.sources.UpdateSource;
import de.scamupdates.state.StateManager;
import de.scamupdates.utils.AtomicFiles;
import de.scamupdates.utils.Hashing;
import org.bukkit.Bukkit;

import java.util.function.Supplier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Update-Suche und Download/Staging. Alle Netzwerk-/Dateiarbeit laeuft auf dem io-Executor. */
public final class UpdateService {

    public enum Kind { UPDATE_AVAILABLE, UP_TO_DATE, EXCLUDED, UNMATCHED, PENDING_MATCH, PENDING_RESTART, INCOMPATIBLE, NO_RELEASE, ERROR }

    public record PluginStatus(PluginInfo plugin, Kind kind, ProjectRef project, RemoteRelease release, String error) {}

    public record Snapshot(long time, List<PluginStatus> statuses) {
        public List<UpdateCandidate> updates() {
            List<UpdateCandidate> out = new ArrayList<>();
            for (PluginStatus s : statuses) {
                if (s.kind() == Kind.UPDATE_AVAILABLE) out.add(new UpdateCandidate(s.plugin(), s.project(), s.release()));
            }
            return out;
        }
        public long count(Kind k) { return statuses.stream().filter(s -> s.kind() == k).count(); }
        public String fingerprint() {
            List<String> parts = new ArrayList<>();
            for (UpdateCandidate c : updates()) parts.add(c.plugin().name() + ":" + c.fromVersion() + ">" + c.toVersion());
            parts.sort(String::compareTo);
            return String.join("|", parts) + "#" + count(Kind.PENDING_MATCH);
        }
    }

    public record Item(String plugin, String from, String to, boolean ok, String error) {}

    public record DownloadReport(List<Item> items, boolean abortedAllOrNothing) {
        public long okCount() { return items.stream().filter(Item::ok).count(); }
        public long failedCount() { return items.stream().filter(i -> !i.ok()).count(); }
    }

    /** Fortschritt der laufenden/letzten Pruefung (fuer GUIs). */
    public record CheckProgress(int done, int total, String current, long startedAt, String operationId) {}

    public static final class BusyException extends RuntimeException {
        public BusyException(String op) { super(op); }
    }

    private final ScamUpdates plugin;
    private final ConfigManager config;
    private final StateManager state;
    private final ScamLog log;
    private final HttpService http;
    private final SourceRegistry registry;
    private final MatchService matches;
    private final TransactionStore transactions;
    private final Stager stager;
    private final OperationLock lock;
    private final ExecutorService io;
    private final Path pluginsDir;
    private final Path tmpDir;
    private final OperationRegistry ops;
    private final PaperDownloads paper;

    private volatile HistoryStore history;
    private volatile ConnectivityGuard connectivity;
    private volatile UpdateQueueStore queue;
    private volatile Supplier<List<PluginInfo>> pluginCollector = PluginScanner::collectMetadata;
    private volatile Supplier<String> mcVersion = Bukkit::getMinecraftVersion;
    private volatile BooleanSupplier restartAvailable = () -> true;
    private volatile int paperBuild = -1;
    private volatile CheckProgress checkProgress;
    private volatile boolean cancelRequested;
    private volatile PaperDownloads.PaperStatus paperStatus;
    private volatile Snapshot snapshot = new Snapshot(0, List.of());
    private volatile long lastCheckStarted;

    public UpdateService(ScamUpdates plugin, ConfigManager config, StateManager state, ScamLog log, HttpService http,
                         SourceRegistry registry, MatchService matches, TransactionStore transactions, Stager stager,
                         OperationLock lock, ExecutorService io, Path pluginsDir, Path tmpDir,
                         OperationRegistry ops, PaperDownloads paper) {
        this.ops = ops;
        this.paper = paper;
        this.plugin = plugin;
        this.config = config;
        this.state = state;
        this.log = log;
        this.http = http;
        this.registry = registry;
        this.matches = matches;
        this.transactions = transactions;
        this.stager = stager;
        this.lock = lock;
        this.io = io;
        this.pluginsDir = pluginsDir;
        this.tmpDir = tmpDir;
    }

    public Snapshot snapshot() { return snapshot; }

    public void setHistory(HistoryStore history) { this.history = history; }
    public void setConnectivity(ConnectivityGuard connectivity) { this.connectivity = connectivity; }
    public void setQueue(UpdateQueueStore queue) { this.queue = queue; }
    public void setPluginCollector(Supplier<List<PluginInfo>> pluginCollector) { this.pluginCollector = pluginCollector; }
    public void setMcVersion(Supplier<String> mcVersion) { this.mcVersion = mcVersion; }
    public void setRestartAvailable(BooleanSupplier s) { this.restartAvailable = s; }
    public void setPaperBuild(int build) { this.paperBuild = build; }
    public CheckProgress checkProgress() { return checkProgress; }
    public PaperDownloads.PaperStatus paperStatus() { return paperStatus; }
    public boolean checkRunning() { return "check".equals(lock.holder()); }

    /** Basis fuer Einzelplugin-Updates: liefert den aktuellen Pruefstatus eines Plugins. */
    public Optional<PluginStatus> statusFor(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return Optional.empty();
        return snapshot.statuses().stream()
                .filter(s -> s.plugin().name().equalsIgnoreCase(pluginName.trim()))
                .findFirst();
    }

    /** Basis fuer Einzelplugin-Updates: liefert den Update-Kandidaten eines Plugins, falls verfuegbar. */
    public Optional<UpdateCandidate> candidateFor(String pluginName) {
        return statusFor(pluginName)
                .filter(s -> s.kind() == Kind.UPDATE_AVAILABLE)
                .map(s -> new UpdateCandidate(s.plugin(), s.project(), s.release()));
    }

    /** Bricht eine laufende Pruefung ab (Vorgang wird CANCELLED, bisherige Ergebnisse bleiben erhalten). */
    public boolean cancelCheck() {
        if (!checkRunning()) return false;
        cancelRequested = true;
        return true;
    }

    private void record(HistoryEntry e) {
        HistoryStore h = history;
        if (h != null) h.append(e);
    }

    public long secondsUntilNextManualCheck() {
        if (config.allowMultipleChecks()) return 0;
        long wait = config.checkCooldownSeconds() - (System.currentTimeMillis() - lastCheckStarted) / 1000;
        return Math.max(0, wait);
    }

    // ------------------------------------------------------------------ Suche

    /** Muss auf dem Hauptthread gestartet werden (Plugin-Metadaten). */
    public CompletableFuture<Snapshot> check() { return check("system"); }

    public CompletableFuture<Snapshot> check(String actor) {
        CompletableFuture<Snapshot> result = new CompletableFuture<>();
        try {
            state.requireActive();
        } catch (IllegalStateException e) {
            result.completeExceptionally(e);
            return result;
        }
        Optional<OperationLock.Handle> handle = lock.tryAcquire("check");
        if (handle.isEmpty()) {
            result.completeExceptionally(new BusyException(lock.holder()));
            return result;
        }
        List<PluginInfo> metas = pluginCollector.get();
        try {
            io.submit(() -> {
                Operation op = null;
                try (OperationLock.Handle h = handle.get()) {
                    if (!state.isOnline() && connectivity != null) connectivity.ensureOnlineOrRefresh();
                    state.requireOnline();

                    lastCheckStarted = System.currentTimeMillis();
                    cancelRequested = false;
                    op = ops.begin(OpType.CHECK, "check", actor).orElse(null);
                    checkProgress = new CheckProgress(0, metas.size(), "", lastCheckStarted, op == null ? "" : op.id());
                    log.info("Update-Pruefung gestartet (" + metas.size() + " Plugins erkannt).");

                    Snapshot s = runCheck(metas, op);
                    snapshot = s;
                    String summary = s.count(Kind.UPDATE_AVAILABLE) + " Update(s), " + s.count(Kind.PENDING_MATCH)
                            + " Zuordnung(en) offen, " + s.count(Kind.ERROR) + " Fehler";
                    log.info("Update-Pruefung beendet: " + summary + ".");
                    if (op != null) ops.finish(op, OpStatus.SUCCEEDED, summary);
                    record(HistoryEntry.of("check", s.count(Kind.ERROR) > 0 ? "WARN" : "INFO", actor, summary));
                    if (connectivity != null && paperStatus != null && paperStatus.failed()
                            && !metas.isEmpty() && s.count(Kind.ERROR) == metas.size()) {
                        connectivity.refresh();
                    }
                    result.complete(s);
                } catch (StateManager.OfflineException e) {
                    log.warn("Update-Pruefung blockiert (offline): " + e.getMessage());
                    result.completeExceptionally(e);
                } catch (CancellationException e) {
                    log.info("Update-Pruefung von " + actor + " abgebrochen.");
                    if (op != null) ops.finish(op, OpStatus.CANCELLED, "abgebrochen");
                    record(HistoryEntry.of("check", "INFO", actor, "Pruefung abgebrochen"));
                    result.completeExceptionally(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (op != null) ops.finish(op, OpStatus.CANCELLED, "unterbrochen");
                    result.completeExceptionally(e);
                } catch (Throwable t) {
                    log.error("Update-Pruefung fehlgeschlagen", t);
                    if (op != null) ops.finish(op, OpStatus.FAILED, log.redact(String.valueOf(t.getMessage())));
                    record(HistoryEntry.of("check", "FAILED", actor, log.redact(String.valueOf(t.getMessage()))));
                    result.completeExceptionally(t);
                }
            });
        } catch (RuntimeException e) {
            handle.get().close();
            result.completeExceptionally(e);
        }
        return result;
    }

    /** Prueft gezielt ein einzelnes installiertes Plugin und aktualisiert seinen Eintrag im Snapshot. */
    public CompletableFuture<PluginStatus> checkSingle(String pluginName, String actor) {
        CompletableFuture<PluginStatus> result = new CompletableFuture<>();
        try {
            state.requireActive();
        } catch (IllegalStateException e) {
            result.completeExceptionally(e);
            return result;
        }
        Optional<OperationLock.Handle> handle = lock.tryAcquire("single-check");
        if (handle.isEmpty()) {
            result.completeExceptionally(new BusyException(lock.holder()));
            return result;
        }
        List<PluginInfo> metas = pluginCollector.get();
        try {
            io.submit(() -> {
                Operation op = null;
                try (OperationLock.Handle h = handle.get()) {
                    if (!state.isOnline() && connectivity != null) connectivity.ensureOnlineOrRefresh();
                    state.requireOnline();

                    List<PluginInfo> plugins = PluginJarIndex.attach(metas, pluginsDir);
                    PluginInfo target = plugins.stream()
                            .filter(p -> p.name().equalsIgnoreCase(pluginName))
                            .findFirst()
                            .orElse(null);
                    if (target == null) {
                        result.completeExceptionally(new IllegalArgumentException("Plugin nicht gefunden: " + pluginName));
                        return;
                    }
                    op = ops.begin(OpType.CHECK, target.name(), actor).orElse(null);
                    PluginStatus st;
                    if (transactions.get(target.name()).isPresent()) {
                        st = new PluginStatus(target, Kind.PENDING_RESTART, null, null, null);
                    } else {
                        try {
                            st = checkOne(target, mcVersion.get());
                        } catch (IOException e) {
                            st = new PluginStatus(target, Kind.ERROR, null, null, log.redact(String.valueOf(e.getMessage())));
                        }
                    }
                    replaceSnapshotEntry(st);
                    String summary = target.name() + ": " + st.kind()
                            + (st.release() == null ? "" : " (" + st.release().version() + ")");
                    if (op != null) ops.finish(op, st.kind() == Kind.ERROR ? OpStatus.FAILED : OpStatus.SUCCEEDED, summary);
                    record(HistoryEntry.of("single-check", st.kind() == Kind.ERROR ? "WARN" : "INFO", actor, summary));
                    result.complete(st);
                } catch (StateManager.OfflineException e) {
                    log.warn("Einzelpruefung blockiert (offline): " + e.getMessage());
                    result.completeExceptionally(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (op != null) ops.finish(op, OpStatus.CANCELLED, "unterbrochen");
                    result.completeExceptionally(e);
                } catch (Throwable t) {
                    log.error("Einzelpruefung fehlgeschlagen fuer " + pluginName, t);
                    if (op != null) ops.finish(op, OpStatus.FAILED, log.redact(String.valueOf(t.getMessage())));
                    record(HistoryEntry.of("single-check", "FAILED", actor, pluginName + ": " + log.redact(String.valueOf(t.getMessage()))));
                    result.completeExceptionally(t);
                }
            });
        } catch (RuntimeException e) {
            handle.get().close();
            result.completeExceptionally(e);
        }
        return result;
    }

    private synchronized void replaceSnapshotEntry(PluginStatus st) {
        Snapshot old = snapshot;
        List<PluginStatus> next = new ArrayList<>();
        boolean replaced = false;
        for (PluginStatus cur : old.statuses()) {
            if (cur.plugin().name().equalsIgnoreCase(st.plugin().name())) {
                next.add(st);
                replaced = true;
            } else {
                next.add(cur);
            }
        }
        if (!replaced) next.add(st);
        long t = old.time() > 0 ? old.time() : System.currentTimeMillis();
        snapshot = new Snapshot(t, next);
    }

    private Snapshot runCheck(List<PluginInfo> metas, Operation op) throws InterruptedException, IOException {
        state.requireOnline();
        List<PluginInfo> plugins = PluginJarIndex.attach(metas, pluginsDir);
        String mc = mcVersion.get();
        long started = checkProgress == null ? System.currentTimeMillis() : checkProgress.startedAt();
        String opId = op == null ? "" : op.id();
        int total = plugins.size();
        checkProgress = new CheckProgress(0, total, "Paper", started, opId);
        paperStatus = paper != null ? paper.status(mc, paperBuild) : null;
        List<PluginStatus> out = new ArrayList<>();
        int done = 0;
        for (PluginInfo p : plugins) {
            state.requireOnline();
            if (cancelRequested) throw new CancellationException("abgebrochen");
            checkProgress = new CheckProgress(done, total, p.name(), started, opId);
            if (op != null) ops.progress(op, total == 0 ? 100 : done * 100 / total, p.name());
            if (transactions.get(p.name()).isPresent()) {
                out.add(new PluginStatus(p, Kind.PENDING_RESTART, null, null, null));
                done++;
                continue;
            }
            try {
                out.add(checkOne(p, mc));
            } catch (IOException e) {
                log.warn("Pruefung von " + p.name() + " fehlgeschlagen: " + e.getMessage());
                out.add(new PluginStatus(p, Kind.ERROR, null, null, log.redact(String.valueOf(e.getMessage()))));
            }
            done++;
            Thread.sleep(Math.max(0, config.requestDelayMs()));
        }
        checkProgress = new CheckProgress(total, total, "", started, opId);
        return new Snapshot(System.currentTimeMillis(), out);
    }

    private PluginStatus checkOne(PluginInfo p, String mc) throws IOException, InterruptedException {
        MatchService.Resolution r = matches.resolve(p);
        switch (r.type()) {
            case EXCLUDED: return new PluginStatus(p, Kind.EXCLUDED, null, null, null);
            case PENDING: return new PluginStatus(p, Kind.PENDING_MATCH, null, null, null);
            case UNMATCHED: return new PluginStatus(p, Kind.UNMATCHED, null, null, null);
            default: break;
        }
        ProjectRef project = r.project();
        if (p.jarPath() == null) return new PluginStatus(p, Kind.ERROR, project, null, "JAR-Datei im plugins-Ordner nicht gefunden");
        if (!registry.enabled(project.sourceId())) return new PluginStatus(p, Kind.ERROR, project, null, "Quelle " + project.sourceId() + " ist deaktiviert");
        UpdateSource source = registry.get(project.sourceId()).orElse(null);
        if (source == null) return new PluginStatus(p, Kind.ERROR, project, null, "Unbekannte Quelle " + project.sourceId());

        Channel channel = config.manualMapping(p.name()).map(ManualMapping::channel).orElse(null);
        if (channel == null) channel = config.defaultChannel();
        ReleaseQuery q = new ReleaseQuery(channel, mc, config.requireMinecraftMatch(), p);
        Optional<RemoteRelease> rel = source.latest(project, q);
        if (rel.isEmpty()) return new PluginStatus(p, Kind.NO_RELEASE, project, null, null);
        RemoteRelease release = rel.get();

        if (config.requireMinecraftMatch() && !release.gameVersions().isEmpty() && mc != null
                && !McVersionMatcher.anyMatches(release.gameVersions(), mc)) {
            return new PluginStatus(p, Kind.INCOMPATIBLE, project, release, null);
        }
        boolean newer;
        if (release.hashDecides()) {
            String expected = release.hashes().get("SHA-256");
            newer = expected != null && !Hashing.sha256(p.jarPath()).equalsIgnoreCase(expected);
        } else {
            newer = VersionComparator.isNewer(release.version(), p.version(), config.unknownOrderAsUpdate());
        }
        return new PluginStatus(p, newer ? Kind.UPDATE_AVAILABLE : Kind.UP_TO_DATE, project, release, null);
    }

    // --------------------------------------------------------------- Download

    /** Laedt und validiert alle Kandidaten, merkt sie danach in plugins/update/ vor. Erst NACH Validierung wird irgendetwas veraendert. */
    public CompletableFuture<DownloadReport> download(List<UpdateCandidate> candidates, Consumer<Item> progress) {
        return download(candidates, progress, "system");
    }

    public CompletableFuture<DownloadReport> download(List<UpdateCandidate> candidates, Consumer<Item> progress, String actor) {
        return downloadInternal(candidates, progress, actor, OpType.DOWNLOAD, "plugins", "download");
    }

    /** Basis fuer Einzelplugin-Updates: laedt und merkt gezielt ein einzelnes Plugin vor. */
    public CompletableFuture<DownloadReport> downloadSingle(String pluginName, Consumer<Item> progress, String actor) {
        Optional<UpdateCandidate> candidate = candidateFor(pluginName);
        if (candidate.isEmpty()) {
            CompletableFuture<DownloadReport> f = new CompletableFuture<>();
            f.complete(new DownloadReport(List.of(), false));
            return f;
        }
        return downloadSingle(candidate.get(), progress, actor);
    }

    public CompletableFuture<DownloadReport> downloadSingle(UpdateCandidate candidate, Consumer<Item> progress, String actor) {
        if (candidate == null) {
            CompletableFuture<DownloadReport> f = new CompletableFuture<>();
            f.complete(new DownloadReport(List.of(), false));
            return f;
        }
        return downloadInternal(List.of(candidate), progress, actor, OpType.SINGLE_UPDATE,
                candidate.plugin().name(), "single-update");
    }

    private CompletableFuture<DownloadReport> downloadInternal(List<UpdateCandidate> candidates, Consumer<Item> progress,
                                                             String actor, OpType opType, String opKey, String lockName) {
        CompletableFuture<DownloadReport> result = new CompletableFuture<>();
        try {
            state.requireActive();
        } catch (IllegalStateException e) {
            result.completeExceptionally(e);
            return result;
        }
        Optional<OperationLock.Handle> handle = lock.tryAcquire(lockName);
        if (handle.isEmpty()) {
            result.completeExceptionally(new BusyException(lock.holder()));
            return result;
        }
        try {
            io.submit(() -> {
                Optional<Operation> op = Optional.empty();
                try (OperationLock.Handle h = handle.get()) {
                    if (!state.isOnline() && connectivity != null) connectivity.ensureOnlineOrRefresh();
                    state.requireOnline();

                    op = ops.begin(opType, opKey, actor);
                    DownloadReport report = runDownload(candidates, progress, op.orElse(null));
                    if (!report.abortedAllOrNothing()) {
                        List<String> okNames = report.items().stream().filter(Item::ok).map(Item::plugin).toList();
                        if (!okNames.isEmpty()) {
                            markPending(okNames);
                        }
                    }
                    boolean noRestart = report.okCount() > 0 && !restartAvailable.getAsBoolean();
                    String result0 = report.okCount() == 0 ? "FAILED" : (report.failedCount() > 0 || noRestart || report.abortedAllOrNothing() ? "WARN" : "OK");
                    String msg = report.okCount() + " vorgemerkt, " + report.failedCount() + " fehlgeschlagen"
                            + (noRestart ? "; KEIN Neustart-Mechanismus verfuegbar - Updates nur vorbereitet" : "")
                            + (report.abortedAllOrNothing() ? "; abgebrochen (all-or-nothing)" : "");
                    List<HistoryEntry.Detail> details = new ArrayList<>();
                    for (Item it : report.items()) {
                        details.add(new HistoryEntry.Detail(it.plugin(), it.from(), it.to(), it.ok() ? "OK" : "FEHLER: " + it.error()));
                    }
                    record(HistoryEntry.of("plugin-update", result0, actor, msg).withDetails(details).withMcTarget(mcVersion.get()));
                    op.ifPresent(o -> ops.finish(o, report.okCount() > 0 ? OpStatus.SUCCEEDED : OpStatus.FAILED, msg));
                    result.complete(report);
                } catch (StateManager.OfflineException e) {
                    log.warn("Download blockiert (offline): " + e.getMessage());
                    result.completeExceptionally(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    op.ifPresent(o -> ops.finish(o, OpStatus.CANCELLED, "unterbrochen"));
                    result.completeExceptionally(e);
                } catch (Throwable t) {
                    log.error("Download-Vorgang fehlgeschlagen", t);
                    op.ifPresent(o -> ops.finish(o, OpStatus.FAILED, log.redact(String.valueOf(t.getMessage()))));
                    record(HistoryEntry.of("plugin-update", "FAILED", actor, log.redact(String.valueOf(t.getMessage()))));
                    result.completeExceptionally(t);
                }
            });
        } catch (RuntimeException e) {
            handle.get().close();
            result.completeExceptionally(e);
        }
        return result;
    }

    private DownloadReport runDownload(List<UpdateCandidate> candidates, Consumer<Item> progress, Operation op) throws InterruptedException {
        state.requireOnline();
        log.info("Update-Transaktion gestartet: " + candidates.size() + " Plugin(s).");
        DownloadPipeline pipeline = new DownloadPipeline(tmpDir,
                (url, target) -> http.download(url, target, config.maxDownloadBytes(), Duration.ofSeconds(config.downloadTimeoutSeconds())),
                mcVersion.get(), config.requireChecksum());

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, config.maxParallelDownloads()));
        List<Item> items = new ArrayList<>();
        List<DownloadPipeline.Prepared> prepared = new ArrayList<>();
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (UpdateCandidate c : candidates) {
                Callable<Object> task = () -> {
                    try {
                        if (transactions.get(c.plugin().name()).isPresent()) {
                            return new Item(c.plugin().name(), c.fromVersion(), c.toVersion(), false, "bereits vorgemerkt, Neustart noetig");
                        }
                        if (c.plugin().jarPath() == null) {
                            return new Item(c.plugin().name(), c.fromVersion(), c.toVersion(), false, "installierte JAR nicht gefunden");
                        }
                        log.info("Download: " + c.plugin().name() + " " + c.fromVersion() + " -> " + c.toVersion() + " (" + c.project().sourceId() + ")");
                        return pipeline.prepare(c);
                    } catch (DownloadPipeline.DownloadException e) {
                        return new Item(c.plugin().name(), c.fromVersion(), c.toVersion(), false, log.redact(e.getMessage()));
                    }
                };
                futures.add(pool.submit(task));
            }
            for (int i = 0; i < futures.size(); i++) {
                Object o;
                try {
                    o = futures.get(i).get();
                } catch (ExecutionException e) {
                    UpdateCandidate c = candidates.get(i);
                    o = new Item(c.plugin().name(), c.fromVersion(), c.toVersion(), false, "interner Fehler: " + e.getCause());
                }
                if (o instanceof DownloadPipeline.Prepared p) prepared.add(p);
                else {
                    Item it = (Item) o;
                    items.add(it);
                    log.warn("Update fehlgeschlagen: " + it.plugin() + ": " + it.error());
                    progress.accept(it);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        boolean abort = config.allOrNothing() && !items.isEmpty();
        if (abort) {
            for (DownloadPipeline.Prepared p : prepared) AtomicFiles.deleteQuietly(p.tmp());
            log.warn("Update-Transaktion abgebrochen (all-or-nothing), nichts wurde vorgemerkt.");
            return new DownloadReport(items, true);
        }
        for (DownloadPipeline.Prepared p : prepared) {
            UpdateCandidate c = p.candidate();
            Item item;
            try {
                Stager.Result sr = stager.stage(c.plugin().name(), c.fromVersion(), c.plugin().jarPath(), p.tmp());
                transactions.put(new TransactionStore.Pending(c.plugin().name(), TransactionStore.Kind.UPDATE,
                        c.fromVersion(), c.toVersion(), sr.stagedFile().getFileName().toString(), sr.sha256(), System.currentTimeMillis()));
                if (queue != null) queue.remove(c.plugin().name());
                item = new Item(c.plugin().name(), c.fromVersion(), c.toVersion(), true, null);
                log.info("Update vorgemerkt: " + c.plugin().name() + " " + c.fromVersion() + " -> " + c.toVersion());
            } catch (IOException e) {
                AtomicFiles.deleteQuietly(p.tmp());
                item = new Item(c.plugin().name(), c.fromVersion(), c.toVersion(), false, log.redact("Staging fehlgeschlagen: " + e.getMessage()));
                log.error("Staging fehlgeschlagen fuer " + c.plugin().name(), e);
            }
            items.add(item);
            progress.accept(item);
            if (op != null) ops.progress(op, items.size() * 100 / Math.max(1, candidates.size()), c.plugin().name());
        }
        cleanTmp();
        return new DownloadReport(items, false);
    }

    /** Markiert erfolgreich vorgemerkte Plugins im aktuellen Snapshot als "Neustart noetig". */
    public synchronized void markPending(java.util.Collection<String> pluginNames) {
        Snapshot old = snapshot;
        List<PluginStatus> updated = new ArrayList<>();
        for (PluginStatus st : old.statuses()) {
            boolean hit = pluginNames.stream().anyMatch(n -> n.equalsIgnoreCase(st.plugin().name()));
            updated.add(hit ? new PluginStatus(st.plugin(), Kind.PENDING_RESTART, st.project(), st.release(), null) : st);
        }
        snapshot = new Snapshot(old.time(), updated);
    }

    /** Markiert ein Plugin im aktuellen Snapshot als ausgeschlossen (z. B. nach "Keins davon"). */
    public synchronized void markExcluded(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return;
        Snapshot old = snapshot;
        List<PluginStatus> updated = new ArrayList<>();
        for (PluginStatus st : old.statuses()) {
            if (st.plugin().name().equalsIgnoreCase(pluginName.trim())) {
                updated.add(new PluginStatus(st.plugin(), Kind.EXCLUDED, null, null, null));
            } else {
                updated.add(st);
            }
        }
        snapshot = new Snapshot(old.time(), updated);
    }

    /** Entfernt einen veralteten PENDING_MATCH-Eintrag aus dem Snapshot nach manueller Zuordnung. */
    public synchronized void clearPendingMatch(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return;
        Snapshot old = snapshot;
        List<PluginStatus> updated = new ArrayList<>();
        for (PluginStatus st : old.statuses()) {
            if (st.plugin().name().equalsIgnoreCase(pluginName.trim()) && st.kind() == Kind.PENDING_MATCH) {
                continue;
            }
            updated.add(st);
        }
        snapshot = new Snapshot(old.time(), updated);
    }

    /** Raeumt verwaiste Temp-Dateien in ScamUpdates/tmp (nur eigene .part-Dateien). */
    public void cleanTmp() {
        if (!Files.isDirectory(tmpDir)) return;
        try (var ds = Files.newDirectoryStream(tmpDir, "*.part")) {
            for (Path p : ds) AtomicFiles.deleteQuietly(p);
        } catch (IOException e) {
            log.debug("Temp-Bereinigung fehlgeschlagen: " + e.getMessage());
        }
    }
}
