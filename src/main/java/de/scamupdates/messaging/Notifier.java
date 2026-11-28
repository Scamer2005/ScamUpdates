package de.scamupdates.messaging;

import de.scamupdates.ScamUpdates;
import de.scamupdates.config.ConfigManager;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.queue.QueueService;
import de.scamupdates.restart.RestartCoordinator;
import de.scamupdates.security.Perm;
import de.scamupdates.state.PluginState;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.UpdateService;
import de.scamupdates.utils.AtomicFiles;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Genau eine uebersichtliche Nachricht pro Admin; Fehlerzustaende nur an berechtigte Admins (einmal pro Join). */
public final class Notifier {

    private record Seen(String fingerprint, long time) {}

    private final ScamUpdates plugin;
    private final ConfigManager config;
    private final Messages messages;
    private final StateManager state;
    private final ScamLog log;
    private final Path file;
    private final Map<UUID, Seen> seen = new HashMap<>();
    private final Object writeLock = new Object();
    private long writeSeq;
    private boolean loaded;

    public Notifier(ScamUpdates plugin, ConfigManager config, Messages messages, StateManager state, ScamLog log, Path file) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.state = state;
        this.log = log;
        this.file = file;
    }

    public Component updateMessage(int count, int pendingMatches) {
        List<Component> parts = new ArrayList<>();
        if (count > 0) {
            parts.add(messages.render("updates.available", Messages.ph("count", count)));
            parts.add(messages.row(
                    messages.button("show-updates", "/scamupdates list updates"),
                    messages.button("update-now", "/scamupdates download"),
                    messages.button("later", "/scamupdates later")));
        }
        if (pendingMatches > 0) parts.add(messages.render("updates.pending-match", Messages.ph("count", pendingMatches)));
        return Component.join(JoinConfiguration.newlines(), parts);
    }

    /** Hauptthread. */
    public void onJoin(Player p) {
        if (!p.isOnline() || !Perm.NOTIFY.allows(n -> p.hasPermission(n))) return;
        if (state.state().isDisabled()) {
            sendStateError(p);
            return;
        }
        if (!state.isOperational() || plugin.updates() == null) return;
        if (plugin.craftyDegraded()) {
            sendCraftyDegraded(p, plugin.controlStatus().detail());
        }
        if (state.state() == PluginState.ACTIVE_OFFLINE) {
            sendOfflineNotice(p);
        }
        if (plugin.restartMechanismFailed()) {
            p.sendMessage(messages.render("restart.mechanism-missing", Messages.ph("reason", plugin.restartDetail())));
        }
        if (!state.isOnline() || !config.notificationsEnabled()) return;
        notifyIfNeeded(p);
        if (config.queueNotifyOnJoin()) notifyQueueOnJoin(p);
    }

    /** Hauptthread: nach einer automatischen Pruefung alle online Admins informieren (jeweils hoechstens einmal). */
    public void broadcastToOnlineAdmins() {
        if (!state.isOnline() || !config.notificationsEnabled() || plugin.updates() == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Perm.NOTIFY.allows(n -> p.hasPermission(n))) notifyIfNeeded(p);
        }
    }

    /** Baut eine klickbare Warteschlangen-Benachrichtigung mit Jetzt aktualisieren / Spaeter / Nicht mehr erinnern. */
    public Component queueMessage(QueueService.EvaluatedEntry ev) {
        String name = ev.entry().plugin();
        if (ev.state() == QueueService.EntryState.AMBIGUOUS) {
            return Component.join(JoinConfiguration.newlines(),
                    messages.render("queue.notify-ambiguous", Messages.ph("plugin", name), Messages.ph("count", ev.candidates().size())),
                    messages.row(
                            messages.button("queue-choose", "/scamupdates match " + name),
                            messages.button("pick-none", "/scamupdates pick " + name + " 0"),
                            messages.button("queue-later", "/scamupdates queue later " + name),
                            messages.button("queue-dismiss", "/scamupdates queue dismiss " + name)));
        }
        String from = ev.candidate() != null ? ev.candidate().fromVersion() : ev.entry().installedVersion();
        String to = ev.candidate() != null ? ev.candidate().toVersion() : ev.entry().targetVersion();
        return Component.join(JoinConfiguration.newlines(),
                messages.render("queue.notify-ready", Messages.ph("plugin", name), Messages.ph("from", from), Messages.ph("to", to)),
                messages.row(
                        messages.button("queue-update-now", "/scamupdates queue update " + name),
                        messages.button("queue-later", "/scamupdates queue later " + name),
                        messages.button("queue-dismiss", "/scamupdates queue dismiss " + name)));
    }

    /** Hauptthread: prueft neu verfuegbare Warteschlangen-Updates und benachrichtigt berechtigte Admins. */
    public void broadcastQueueNotifications() {
        if (!state.isOnline() || !config.notificationsEnabled() || plugin.updates() == null || plugin.queueService() == null) return;
        Set<String> installed = Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(Plugin::getName).collect(Collectors.toSet());
        List<QueueService.EvaluatedEntry> pending = plugin.queueService().pendingNotifications(
                plugin.updates().snapshot(), plugin.matches(), installed, System.currentTimeMillis());
        if (pending.isEmpty()) return;
        List<Player> admins = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Perm.NOTIFY.allows(n -> p.hasPermission(n)) || Perm.QUEUE.allows(n -> p.hasPermission(n))) admins.add(p);
        }
        for (QueueService.EvaluatedEntry ev : pending) {
            Component c = queueMessage(ev);
            Bukkit.getConsoleSender().sendMessage(c);
            for (Player p : admins) {
                p.sendMessage(c);
                messages.playSound(p, "notify");
            }
            if (!admins.isEmpty()) {
                plugin.queueService().markNotified(ev);
            }
        }
    }

    private void notifyQueueOnJoin(Player p) {
        if (plugin.queueService() == null || plugin.updates() == null) return;
        Set<String> installed = Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(Plugin::getName).collect(Collectors.toSet());
        long now = System.currentTimeMillis();
        List<QueueService.EvaluatedEntry> list = plugin.queueService().evaluate(plugin.updates().snapshot(), plugin.matches(), installed, now);
        boolean sent = false;
        for (QueueService.EvaluatedEntry ev : list) {
            if (ev.entry().snoozedUntil() > now) continue;
            if (ev.state() == QueueService.EntryState.READY || ev.state() == QueueService.EntryState.AMBIGUOUS) {
                p.sendMessage(queueMessage(ev));
                plugin.queueService().markNotified(ev);
                sent = true;
            }
        }
        if (sent) messages.playSound(p, "notify");
    }

    public void broadcastRestartWarning(RestartCoordinator.ActiveCountdown countdown, int remainingSeconds) {
        String reason = countdown == null || countdown.reason().isBlank() ? "Serverneustart" : countdown.reason();
        Component msg = messages.render("restart.warning",
                Messages.ph("time", RestartCoordinator.formatDuration(remainingSeconds)),
                Messages.ph("reason", reason));
        Bukkit.getConsoleSender().sendMessage(msg);
        String soundKey = remainingSeconds <= 10 ? "restart-countdown" : "restart-warning";
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
            messages.playSound(p, soundKey);
        }
    }

    public void broadcastRestartCancelled(RestartCoordinator.ActiveCountdown previous, String actor) {
        Component msg = messages.render("restart.countdown-cancelled",
                Messages.ph("actor", actor == null || actor.isBlank() ? "system" : actor));
        Bukkit.getConsoleSender().sendMessage(msg);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
        }
    }

    public void broadcastRestartFailed(String actor, String detail) {
        Component msg = messages.render("restart.scheduled-failed",
                Messages.ph("reason", log.redact(String.valueOf(detail))));
        Bukkit.getConsoleSender().sendMessage(msg);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Perm.NOTIFY.allows(n -> p.hasPermission(n)) || Perm.RESTART.allows(n -> p.hasPermission(n))
                    || Perm.SCHEDULE.allows(n -> p.hasPermission(n))) {
                p.sendMessage(msg);
                messages.playSound(p, "error");
            }
        }
    }

    private void notifyIfNeeded(Player p) {
        UpdateService.Snapshot s = plugin.updates().snapshot();
        int count = s.updates().size();
        int pend = config.includePendingMatches() ? plugin.matches().pendingChoices().size() : 0;
        if (count == 0 && pend == 0) return;
        String fp = s.fingerprint();
        if (!shouldNotify(p.getUniqueId(), fp)) return;
        p.sendMessage(updateMessage(count, pend));
        messages.playSound(p, "notify");
        if (config.titleEnabled() && count > 0) {
            p.showTitle(Title.title(messages.mini(config.titleText(), Messages.ph("count", count)),
                    messages.mini(config.subtitleText(), Messages.ph("count", count))));
        }
        markNotified(p.getUniqueId(), fp);
    }

    private synchronized boolean shouldNotify(UUID id, String fp) {
        load();
        Seen s = seen.get(id);
        if (s == null) return true;
        if (config.notificationMode().equals("once-per-update-set") && !s.fingerprint().equals(fp)) return true;
        int minutes = config.renotifyMinutes();
        return minutes > 0 && System.currentTimeMillis() - s.time() >= minutes * 60_000L;
    }

    private void markNotified(UUID id, String fp) {
        final String content;
        final long seq;
        synchronized (this) {
            load();
            seen.put(id, new Seen(fp, System.currentTimeMillis()));
            YamlConfiguration y = new YamlConfiguration();
            y.options().pathSeparator('/');
            seen.forEach((u, v) -> {
                y.set("notified/" + u + "/fingerprint", v.fingerprint());
                y.set("notified/" + u + "/time", v.time());
            });
            content = y.saveToString();
            seq = ++writeSeq;
        }
        try {
            plugin.io().submit(() -> writeSafely(content, seq));
        } catch (RuntimeException e) {
            writeSafely(content, seq);
        }
    }

    private void writeSafely(String content, long seq) {
        synchronized (writeLock) {
            synchronized (this) {
                if (seq < writeSeq) return;
            }
            try {
                AtomicFiles.writeString(file, content);
            } catch (IOException e) {
                log.error("notified.yml konnte nicht gespeichert werden", e);
            }
        }
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(file)) return;
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        try {
            y.loadFromString(Files.readString(file));
        } catch (IOException | InvalidConfigurationException e) {
            log.error("notified.yml nicht lesbar", e);
            return;
        }
        ConfigurationSection sec = y.getConfigurationSection("notified");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                ConfigurationSection s = sec.getConfigurationSection(k);
                if (s != null) seen.put(UUID.fromString(k), new Seen(s.getString("fingerprint", ""), s.getLong("time", 0)));
            } catch (IllegalArgumentException ignored) {
                // ungueltige UUID ignorieren
            }
        }
    }

    // ------------------------------------------------------------ Fehlerzustand

    public Component stateErrorMessage() {
        PluginState st = state.state();
        List<String> d = state.details();
        String joinedList = d.stream().map(x -> "- " + x).collect(Collectors.joining("\n"));
        String first = d.isEmpty() ? "" : d.get(0);
        return switch (st) {
            case DISABLED_MISSING_CONFIG -> messages.render("error.missing-crafty", Messages.ph("missing", joinedList));
            case DISABLED_NO_INTERNET -> messages.render("error.no-internet", Messages.ph("reason", first));
            case DISABLED_CRAFTY_UNAVAILABLE -> messages.render("error.crafty-unavailable", Messages.ph("reason", first));
            case DISABLED_CRAFTY_AUTH_FAILED -> messages.render("error.crafty-auth", Messages.ph("reason", first));
            case DISABLED_CONFIGURATION_ERROR -> messages.render("error.config", Messages.ph("problems", joinedList));
            default -> Component.empty();
        };
    }

    public void sendStateError(CommandSender to) {
        Component c = stateErrorMessage();
        if (c.equals(Component.empty())) return;
        to.sendMessage(c);
        messages.playSound(to, "error");
    }

    public void sendCraftyDegraded(CommandSender to, String detail) {
        to.sendMessage(messages.render("crafty.degraded", Messages.ph("reason", log.redact(String.valueOf(detail)))));
        messages.playSound(to, "error");
    }

    public void sendOfflineNotice(CommandSender to) {
        List<String> d = state.details();
        String reason = d.isEmpty() ? "Keine Internetverbindung" : d.get(0);
        to.sendMessage(messages.render("offline.notice", Messages.ph("reason", log.redact(reason))));
    }
}
