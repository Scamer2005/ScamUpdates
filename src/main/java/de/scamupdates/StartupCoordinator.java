package de.scamupdates;

import de.scamupdates.compat.CompatInfo;
import de.scamupdates.config.ConfigManager;
import de.scamupdates.config.CraftyConfig;
import de.scamupdates.crafty.CraftyCoordinator;
import de.scamupdates.history.HistoryEntry;
import de.scamupdates.security.Perm;
import de.scamupdates.state.PluginState;
import de.scamupdates.update.PostRestartVerifier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Startkoordinator: laedt config.yml, baut die Kernservices sofort auf und fuehrt Internet- sowie
 * optionale Crafty-Pruefungen getrennt durch. Nur eine kaputte config.yml deaktiviert das Plugin;
 * fehlendes Internet schaltet in ACTIVE_OFFLINE, und Crafty-Fehler deaktivieren nur Crafty.
 */
public final class StartupCoordinator {
    private final ScamUpdates plugin;
    private final AtomicInteger generation = new AtomicInteger();
    private volatile BukkitTask periodic;

    public StartupCoordinator(ScamUpdates plugin) { this.plugin = plugin; }

    /** Hauptthread. */
    public void start(boolean firstRun) {
        final int gen = generation.incrementAndGet();
        stopScheduled();
        plugin.confirmations().clear();
        plugin.dropServices();
        plugin.log().clearSecrets();
        plugin.state().set(PluginState.STARTING, List.of());
        plugin.log().info("Startpruefungen laufen ...");

        ConfigManager config = plugin.config();
        ConfigManager.LoadResult lr = config.load();
        if (!lr.ok()) { fail(PluginState.DISABLED_CONFIGURATION_ERROR, lr.problems(), gen); return; }

        if (plugin.gui() != null) plugin.gui().reload();
        CompatInfo ci = CompatInfo.detect(config.testedVersions());
        plugin.setCompat(ci);
        if (ci.isSupported()) plugin.log().info("Minecraft " + ci.minecraft() + " (Paper-Build " + (ci.paperBuild() > 0 ? ci.paperBuild() : "?") + "): " + ci.status() + " - " + ci.detail());
        else plugin.log().warn("Kompatibilitaet: " + ci.status() + " - " + ci.detail());

        CraftyConfig cc = config.crafty();
        plugin.log().registerSecret(cc.apiToken());
        plugin.log().registerSecret(config.githubToken());
        plugin.craftyCoordinator().reset(cc.enabled());

        // Kernservices und lokale Funktionen sofort aktivieren (unabhaengig von Internet und Crafty)
        plugin.buildServices();
        plugin.state().set(PluginState.ACTIVE_OFFLINE, List.of("Netzwerkpruefung laeuft ..."));
        plugin.updates().cleanTmp();
        if (firstRun) verifyPostRestart();
        if (config.historyEnabled()) plugin.history().prune(System.currentTimeMillis());
        if (!cc.enabled()) plugin.evaluateLocalRestart();
        plugin.log().info("Kernfunktionen aktiv. Pruefe Internetverbindung" + (cc.enabled() ? " und Crafty ..." : " (Crafty deaktiviert)."));

        try {
            plugin.io().submit(() -> asyncChecks(cc, gen, firstRun));
        } catch (RuntimeException e) {
            plugin.log().error("Asynchrone Startpruefung konnte nicht gestartet werden", e);
        }
    }

    private void verifyPostRestart() {
        List<PostRestartVerifier.Report> reports = PostRestartVerifier.verify(plugin.transactions(),
                plugin.pluginsDir(), plugin.updateDir(), plugin.log());
        if (!reports.isEmpty()) {
            long ok = reports.stream().filter(x -> x.status() == PostRestartVerifier.Status.APPLIED).count();
            plugin.setPostRestartSummary(ok + "/" + reports.size() + " uebernommen");
            if (plugin.config().historyEnabled()) {
                for (PostRestartVerifier.Report r : reports) {
                    plugin.history().append(HistoryEntry.of("post-restart",
                            r.status() == PostRestartVerifier.Status.APPLIED ? "OK" : "WARN", "system",
                            r.plugin() + " -> " + r.expectedVersion() + ": " + r.status() + " (" + r.detail() + ")"));
                }
            }
        }
    }

    private void asyncChecks(CraftyConfig cc, int gen, boolean firstRun) {
        try {
            if (gen != generation.get()) return;
            boolean online = plugin.connectivity().refresh();
            if (gen != generation.get()) return;

            CraftyCoordinator.Evaluation craftyEval = plugin.craftyCoordinator().evaluateAndConnect(cc, plugin.config().localRestartCommand());
            if (gen != generation.get()) return;

            plugin.runMain(() -> {
                if (gen != generation.get() || !plugin.state().isOperational()) return;
                if (cc.enabled() && craftyEval.degraded()) {
                    plugin.notifier().sendCraftyDegraded(Bukkit.getConsoleSender(), craftyEval.detail());
                    for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                        if (Perm.NOTIFY.allows(n -> p.hasPermission(n))) {
                            plugin.notifier().sendCraftyDegraded(p, craftyEval.detail());
                        }
                    }
                }
                if (!online) {
                    plugin.notifier().sendOfflineNotice(Bukkit.getConsoleSender());
                    for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                        if (Perm.NOTIFY.allows(n -> p.hasPermission(n))) plugin.notifier().sendOfflineNotice(p);
                    }
                }
                schedulePeriodic();
                if (firstRun) schedulePrune();
                if (plugin.config().checkOnStart() && plugin.state().isOnline()) runAutoCheck();
            });
        } catch (Throwable t) {
            plugin.log().error("Unerwarteter Fehler bei den asynchronen Startpruefungen (lokale Funktionen bleiben aktiv)", t);
        }
    }

    private void runAutoCheck() {
        if (!plugin.state().isOnline() || plugin.lock().isBusy() || plugin.updates() == null) return;
        plugin.updates().check().whenComplete((snap, err) -> plugin.runMain(() -> {
            if (err != null) {
                plugin.log().warn("Automatische Pruefung fehlgeschlagen: " + plugin.log().redact(String.valueOf(err.getMessage())));
                return;
            }
            plugin.notifier().broadcastToOnlineAdmins();
        }));
    }

    private void schedulePrune() {
        long day = 24L * 60 * 60 * 20;
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (plugin.state().isOperational() && plugin.config().historyEnabled()) plugin.history().prune(System.currentTimeMillis());
        }, day, day);
    }

    private void schedulePeriodic() {
        int minutes = plugin.config().checkIntervalMinutes();
        if (minutes <= 0) return;
        long ticks = minutes * 60L * 20L;
        periodic = plugin.getServer().getScheduler().runTaskTimer(plugin, this::periodicTick, ticks, ticks);
    }

    private void periodicTick() {
        if (!plugin.state().isOperational()) return;
        if (plugin.state().isOnline()) {
            runAutoCheck();
            return;
        }
        plugin.io().submit(() -> {
            if (plugin.connectivity().refresh()) {
                plugin.runMain(this::runAutoCheck);
            }
        });
    }

    void stopScheduled() {
        BukkitTask t = periodic;
        if (t != null) t.cancel();
        periodic = null;
    }

    private void fail(PluginState s, List<String> details, int gen) {
        if (gen != generation.get()) return;
        plugin.state().set(s, details);
        plugin.log().error("ScamUpdates deaktiviert: " + s + " - " + String.join("; ", details), null);
        plugin.runMain(() -> {
            if (gen != generation.get()) return;
            stopScheduled();
            plugin.dropServices();
            Component msg = plugin.notifier().stateErrorMessage();
            Bukkit.getConsoleSender().sendMessage(msg);
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                if (Perm.NOTIFY.allows(n -> p.hasPermission(n))) plugin.notifier().sendStateError(p);
            }
        });
    }

    public void shutdown() {
        generation.incrementAndGet();
        stopScheduled();
    }
}
