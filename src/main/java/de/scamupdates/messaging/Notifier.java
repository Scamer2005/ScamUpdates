package de.scamupdates.messaging;

import de.scamupdates.ScamUpdates;
import de.scamupdates.config.ConfigManager;
import de.scamupdates.logging.ScamLog;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    }

    /** Hauptthread: nach einer automatischen Pruefung alle online Admins informieren (jeweils hoechstens einmal). */
    public void broadcastToOnlineAdmins() {
        if (!state.isOnline() || !config.notificationsEnabled() || plugin.updates() == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Perm.NOTIFY.allows(n -> p.hasPermission(n))) notifyIfNeeded(p);
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

    private synchronized void markNotified(UUID id, String fp) {
        load();
        seen.put(id, new Seen(fp, System.currentTimeMillis()));
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        seen.forEach((u, v) -> {
            y.set("notified/" + u + "/fingerprint", v.fingerprint());
            y.set("notified/" + u + "/time", v.time());
        });
        String content = y.saveToString();
        try {
            plugin.io().submit(() -> writeSafely(content));
        } catch (RuntimeException e) {
            writeSafely(content);
        }
    }

    private void writeSafely(String content) {
        try {
            AtomicFiles.writeString(file, content);
        } catch (IOException e) {
            log.error("notified.yml konnte nicht gespeichert werden", e);
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
