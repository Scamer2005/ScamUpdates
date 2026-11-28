package de.scamupdates;

import de.scamupdates.backup.BackupManager;
import de.scamupdates.commands.ScamCommand;
import de.scamupdates.compat.CompatInfo;
import de.scamupdates.config.ConfigManager;
import de.scamupdates.crafty.ControlStatus;
import de.scamupdates.crafty.CraftyClient;
import de.scamupdates.crafty.CraftyCoordinator;
import de.scamupdates.gui.GuiService;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.listeners.JoinListener;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.match.MatchService;
import de.scamupdates.match.MatchStore;
import de.scamupdates.messaging.Messages;
import de.scamupdates.messaging.Notifier;
import de.scamupdates.net.ConnectivityGuard;
import de.scamupdates.net.HttpService;
import de.scamupdates.net.InternetCheck;
import de.scamupdates.net.PaperDownloads;
import de.scamupdates.ops.OperationRegistry;
import de.scamupdates.queue.QueueService;
import de.scamupdates.queue.UpdateQueueStore;
import de.scamupdates.restart.LocalRestart;
import de.scamupdates.restart.RestartCoordinator;
import de.scamupdates.restart.RestartScheduleStore;
import de.scamupdates.restart.RestartService;
import de.scamupdates.rollback.RollbackService;
import de.scamupdates.scan.PluginScanner;
import de.scamupdates.security.ConfirmationManager;
import de.scamupdates.security.OperationLock;
import de.scamupdates.security.Perm;
import de.scamupdates.sources.SourceRegistry;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.Stager;
import de.scamupdates.update.TransactionStore;
import de.scamupdates.update.UpdateService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static de.scamupdates.messaging.Messages.ph;

public final class ScamUpdates extends JavaPlugin {

    /** Services, die im betriebsbereiten Zustand (ACTIVE oder ACTIVE_OFFLINE) existieren. */
    private record Services(HttpService http, SourceRegistry registry, MatchService matches, UpdateService updates,
                            RollbackService rollback, RestartService restart) {}

    private ScamLog log;
    private StateManager state;
    private ConfigManager config;
    private Messages messages;
    private OperationLock lock;
    private ExecutorService io;
    private ConfirmationManager confirmations;
    private BackupManager backups;
    private TransactionStore transactions;
    private Stager stager;
    private MatchStore matchStore;
    private Notifier notifier;
    private StartupCoordinator startup;
    private OperationRegistry ops;
    private HistoryStore history;
    private UpdateQueueStore queue;
    private QueueService queueService;
    private RestartScheduleStore restartSchedule;
    private RestartCoordinator restartCoordinator;
    private CraftyCoordinator craftyCoordinator;
    private ConnectivityGuard connectivity;
    private GuiService gui;
    private ScamCommand command;
    private Path pluginsDir;
    private Path updateDir;
    private volatile Services services;
    private volatile String postRestartSummary = "-";
    private volatile CompatInfo compat = CompatInfo.detect(List.of());
    private volatile LocalRestart localRestart;
    private volatile LocalRestart.Status localStatus;

    @Override
    public void onEnable() {
        Path data = getDataFolder().toPath();
        pluginsDir = data.toAbsolutePath().getParent();
        updateDir = Bukkit.getUpdateFolderFile().toPath().toAbsolutePath();

        log = new ScamLog(getLogger(), data.resolve("logs"));
        state = new StateManager();
        config = new ConfigManager(this, log);
        messages = new Messages(config, log);
        lock = new OperationLock();
        AtomicInteger n = new AtomicInteger();
        io = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ScamUpdates-IO-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        confirmations = new ConfirmationManager(System::currentTimeMillis, () -> config.confirmTtlSeconds(),
                () -> config.maxPendingConfirmations(), () -> config.confirmCodeLength());
        backups = new BackupManager(pluginsDir.resolve("update-backup"), log);
        transactions = new TransactionStore(data.resolve("transactions.yml"), log);
        stager = new Stager(updateDir, backups);
        matchStore = new MatchStore(data.resolve("matches.yml"), log);
        ops = new OperationRegistry(data.resolve("operations.yml"), log);
        ops.markInterrupted();
        history = new HistoryStore(data.resolve("history.jsonl"), log, () -> config.historyRetentionDays(), () -> config.historyMaxEntries());
        queue = new UpdateQueueStore(data.resolve("queue.yml"), log);
        queueService = new QueueService(queue, config, log);
        restartSchedule = new RestartScheduleStore(data.resolve("restarts.yml"), log);
        restartCoordinator = new RestartCoordinator(restartSchedule, config, state, log);
        restartCoordinator.setListener(new RestartCoordinator.Listener() {
            @Override
            public void onWarning(RestartCoordinator.ActiveCountdown countdown, int remainingSeconds) {
                runMain(() -> notifier.broadcastRestartWarning(countdown, remainingSeconds));
            }

            @Override
            public void onExecutionFailed(String actor, String detail) {
                runMain(() -> notifier.broadcastRestartFailed(actor, detail));
            }

            @Override
            public void onCancelled(RestartCoordinator.ActiveCountdown previous, String actor) {
                runMain(() -> notifier.broadcastRestartCancelled(previous, actor));
            }
        });
        craftyCoordinator = new CraftyCoordinator(log);
        connectivity = new ConnectivityGuard(state,
                () -> new InternetCheck(config.internetTargets(), Duration.ofMillis(config.internetTimeoutMs()), config.userAgent()).run(),
                log);
        notifier = new Notifier(this, config, messages, state, log, data.resolve("notified.yml"));
        startup = new StartupCoordinator(this);
        gui = new GuiService(this);

        command = new ScamCommand(this);
        for (String name : new String[]{"scamupdates", "update"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(command);
                cmd.setTabCompleter(command);
            }
        }
        warnIfAliasTaken();
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> confirmations.purgeExpired(), 600L, 600L);

        startup.start(true);
    }

    @Override
    public void onDisable() {
        if (startup != null) startup.shutdown();
        if (gui != null) gui.shutdown();
        getServer().getScheduler().cancelTasks(this);
        if (confirmations != null) confirmations.clear();
        if (io != null) io.shutdownNow();     // laufende Downloads brechen ab; Temp-Dateien bleiben ausserhalb von plugins/update
        services = null;
        if (log != null) log.clearSecrets();
    }

    private void warnIfAliasTaken() {
        PluginCommand ours = getCommand("update");
        Command registered = Bukkit.getCommandMap().getCommand("update");
        if (ours != null && registered != ours) {
            log.warn("Der Befehl /update ist bereits von einem anderen Plugin/Server belegt. Verwende /scamupdates "
                    + "oder /scamupdates:update (siehe README, Abschnitt 'Alias-Kollision').");
        }
    }

    // ---------------------------------------------------------------- Services

    /** Baut alle Kernservices unabhängig von Crafty oder bestehender Internetverbindung auf. */
    void buildServices() {
        HttpService http = new HttpService(config.userAgent(), Duration.ofSeconds(config.httpTimeoutSeconds()),
                config.httpRetries(), config.allowHttpDownloads(), log);
        SourceRegistry registry = new SourceRegistry(http, config);
        MatchService matches = new MatchService(config, registry, matchStore, log);
        Path tmp = getDataFolder().toPath().resolve("tmp");
        PaperDownloads paper = new PaperDownloads(http);
        UpdateService updates = new UpdateService(this, config, state, log, http, registry, matches, transactions, stager,
                lock, io, pluginsDir, tmp, ops, paper);
        updates.setConnectivity(connectivity);
        updates.setQueue(queue);
        updates.setPluginCollector(PluginScanner::collectMetadata);
        updates.setMcVersion(Bukkit::getMinecraftVersion);
        RollbackService rollback = new RollbackService(state, backups, stager, transactions, log, lock, io, pluginsDir, tmp,
                Bukkit::getMinecraftVersion);
        RestartService restart = new RestartService(state, config, log, lock, io, transactions, backups, updateDir,
                craftyCoordinator::client, Bukkit::getMinecraftVersion);
        LocalRestart lr = new LocalRestart(pluginsDir.getParent(), config.localRestartCommand());
        this.localRestart = lr;
        restart.setLocal(lr, this::runMainTask);
        updates.setPaperBuild(compat.paperBuild());
        updates.setRestartAvailable(this::restartAvailable);
        if (config.historyEnabled()) {
            updates.setHistory(history);
            rollback.setHistory(history);
            restart.setHistory(history);
            queueService.setHistory(history);
            restartCoordinator.setHistory(history);
        } else {
            queueService.setHistory(null);
            restartCoordinator.setHistory(null);
        }
        restartCoordinator.setRestartService(restart);
        this.services = new Services(http, registry, matches, updates, rollback, restart);
    }

    void dropServices() {
        this.services = null;
        if (restartCoordinator != null) restartCoordinator.setRestartService(null);
    }

    private void runMainTask(Runnable r) { runMain(r); }

    public void runMain(Runnable r) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) r.run();
        else getServer().getScheduler().runTask(this, r);
    }

    // ----------------------------------------------------------- Neustart-Status

    /** Hauptthread: prueft den lokalen /restart-Mechanismus (nur bei crafty.enabled: false). */
    public void evaluateLocalRestart() {
        if (config.craftyEnabled()) return;
        LocalRestart lr = localRestart;
        if (lr == null) return;
        LocalRestart.Status st = lr.evaluate();
        this.localStatus = st;
        craftyCoordinator.setLocalStatus(ControlStatus.disabledLocal(st.ok(), st.detail()));
        if (st.ok()) {
            log.info("Lokaler Neustart bereit: " + st.detail());
            return;
        }
        log.warn("Kein funktionierender Neustart-Mechanismus: " + st.detail());
        Component c = messages.render("restart.mechanism-missing", ph("reason", st.detail()));
        Bukkit.getConsoleSender().sendMessage(c);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Perm.NOTIFY.allows(x -> p.hasPermission(x))) p.sendMessage(c);
        }
    }

    /** Ob nach einem Download ein Neustart tatsaechlich ausgeloest werden kann. */
    public boolean restartAvailable() {
        if (config.craftyEnabled()) {
            return config.crafty().restartEnabled() && craftyCoordinator.isReady();
        }
        LocalRestart.Status s = localStatus;
        return s != null && s.ok();
    }

    /** true, wenn Crafty aktiviert ist, aber wegen Konfigurations-, Auth- oder Verbindungsfehler deaktiviert wurde. */
    public boolean craftyDegraded() {
        return config.craftyEnabled() && craftyCoordinator.isDegraded();
    }

    /** true, wenn der lokale Neustart geprueft wurde und NICHT funktioniert (Warnung an Admins). */
    public boolean restartMechanismFailed() {
        if (config.craftyEnabled()) return false;
        LocalRestart.Status s = localStatus;
        return s != null && !s.ok();
    }

    public String restartDetail() {
        if (config.craftyEnabled()) {
            ControlStatus cs = controlStatus();
            return cs.ready() ? "Crafty Controller" : "Crafty deaktiviert (" + cs.detail() + ")";
        }
        LocalRestart.Status s = localStatus;
        return s == null ? "noch nicht geprueft" : s.detail();
    }

    public void refreshControlStatus(Player requester) {
        if (config.craftyEnabled()) {
            io.submit(() -> {
                CraftyCoordinator.Evaluation eval = craftyCoordinator.evaluateAndConnect(config.crafty(), config.localRestartCommand());
                if (requester != null) {
                    runMain(() -> {
                        if (!requester.isOnline()) return;
                        if (!eval.degraded()) {
                            messages.send(requester, "crafty.connected");
                        } else {
                            notifier.sendCraftyDegraded(requester, eval.detail());
                        }
                    });
                }
            });
        } else {
            runMain(this::evaluateLocalRestart);
        }
    }

    // ---------------------------------------------------------------- Zugriff

    public ScamLog log() { return log; }
    public StateManager state() { return state; }
    public ConfigManager config() { return config; }
    public Messages messages() { return messages; }
    public OperationLock lock() { return lock; }
    public ExecutorService io() { return io; }
    public ConfirmationManager confirmations() { return confirmations; }
    public BackupManager backups() { return backups; }
    public TransactionStore transactions() { return transactions; }
    public Notifier notifier() { return notifier; }
    public StartupCoordinator startup() { return startup; }
    public OperationRegistry ops() { return ops; }
    public HistoryStore history() { return history; }
    public UpdateQueueStore queue() { return queue; }
    public QueueService queueService() { return queueService; }
    public RestartScheduleStore restartSchedule() { return restartSchedule; }
    public RestartCoordinator restartCoordinator() { return restartCoordinator; }
    public CraftyCoordinator craftyCoordinator() { return craftyCoordinator; }
    public ConnectivityGuard connectivity() { return connectivity; }
    public GuiService gui() { return gui; }
    public ScamCommand command() { return command; }
    public Path pluginsDir() { return pluginsDir; }
    public Path updateDir() { return updateDir; }
    public String postRestartSummary() { return postRestartSummary; }
    void setPostRestartSummary(String s) { this.postRestartSummary = s; }
    public CompatInfo compat() { return compat; }
    void setCompat(CompatInfo c) { this.compat = c; }
    public ControlStatus controlStatus() { return craftyCoordinator == null ? ControlStatus.unknown(true) : craftyCoordinator.status(); }

    public UpdateService updates() { Services s = services; return s == null ? null : s.updates(); }
    public MatchService matches() { Services s = services; return s == null ? null : s.matches(); }
    public RollbackService rollback() { Services s = services; return s == null ? null : s.rollback(); }
    public RestartService restart() { Services s = services; return s == null ? null : s.restart(); }
    public SourceRegistry registry() { Services s = services; return s == null ? null : s.registry(); }
}
