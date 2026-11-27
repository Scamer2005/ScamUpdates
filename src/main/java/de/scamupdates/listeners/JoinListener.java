package de.scamupdates.listeners;

import de.scamupdates.ScamUpdates;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class JoinListener implements Listener {
    private final ScamUpdates plugin;

    public JoinListener(ScamUpdates plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long delay = Math.max(1, plugin.config().joinDelayTicks());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.notifier().onJoin(player), delay);
    }
}
