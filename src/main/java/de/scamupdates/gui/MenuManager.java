package de.scamupdates.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Routet Inventar-Events zu Menues. Der gemeinsame Code greift NIE auf InventoryView zu (Klasse/Interface-Wechsel
 * in 1.21), sondern vergleicht ausschliesslich Inventar-Instanzen.
 */
final class MenuManager implements Listener {
    private final GuiService gui;
    private final Map<UUID, Menu> open = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private BukkitTask ticker;

    MenuManager(GuiService gui) { this.gui = gui; }

    void startTicker() {
        ticker = gui.plugin().getServer().getScheduler().runTaskTimer(gui.plugin(), this::tick, 20L, gui.config().refreshTicks());
    }

    private void tick() {
        for (Map.Entry<UUID, Menu> e : new ArrayList<>(open.entrySet())) {
            Menu m = e.getValue();
            if (!m.viewer.isOnline()) {
                open.remove(e.getKey());
                continue;
            }
            try {
                m.tick();
            } catch (RuntimeException ex) {
                gui.plugin().log().error("GUI-Aktualisierung fehlgeschlagen (" + m.getClass().getSimpleName() + ")", ex);
            }
        }
    }

    /** Zeigt das Menue; ein bisher offenes Menue desselben Spielers wird sauber abgeschlossen. */
    void show(Player p, Menu menu, Inventory inventory) {
        Menu prev = open.put(p.getUniqueId(), menu);
        if (prev != null && prev != menu) prev.closed();
        p.openInventory(inventory);
    }

    boolean isOpen(Player p, Menu menu) {
        return p != null && menu != null && p.isOnline() && open.get(p.getUniqueId()) == menu;
    }

    boolean cooldownOk(UUID id) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(id);
        if (last != null && now - last < gui.config().clickCooldownMs()) return false;
        lastClick.put(id, now);
        return true;
    }

    void closeAll() {
        if (ticker != null) ticker.cancel();
        for (Menu m : new ArrayList<>(open.values())) {
            try {
                m.viewer.closeInventory();
            } catch (RuntimeException ignored) {
                // Spieler bereits getrennt
            }
        }
        open.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Menu m = open.get(p.getUniqueId());
        if (m == null || m.inventory() != e.getInventory()) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        m.click(e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Menu m = open.get(p.getUniqueId());
        if (m != null && m.inventory() == e.getInventory()) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Menu m = open.get(p.getUniqueId());
        if (m != null && m.inventory() == e.getInventory()) {
            open.remove(p.getUniqueId());
            m.closed();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        open.remove(e.getPlayer().getUniqueId());
        lastClick.remove(e.getPlayer().getUniqueId());
    }
}
