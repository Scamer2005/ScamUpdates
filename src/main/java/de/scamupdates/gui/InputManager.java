package de.scamupdates.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static de.scamupdates.messaging.Messages.ph;

/**
 * Texteingabe ohne NMS. Anvil: ein echtes (virtuelles) Amboss-Fenster ueber Player#openAnvil; der Text wird aus
 * PrepareAnvilEvent gelesen und mit einem Klick auf das Papier (Slot 0 oder Ergebnis) bestaetigt, damit keine
 * Erfahrungslevel noetig sind. Faellt das Oeffnen aus oder ist input-method: chat gesetzt, wird per Chat eingegeben.
 */
final class InputManager implements Listener {
    private final GuiService gui;
    private final Map<UUID, Session> awaiting = new HashMap<>();
    private final Map<UUID, Session> anvil = new HashMap<>();
    private final Map<UUID, ChatSession> chat = new HashMap<>();

    InputManager(GuiService gui) { this.gui = gui; }

    // ------------------------------------------------------------------ Start

    void ask(Player p, TextPrompt prompt) {
        cancelExisting(p);
        if (gui.config().inputMethod().equals("anvil") && openAnvil(p, prompt)) return;
        openChat(p, prompt);
    }

    private void cancelExisting(Player p) {
        UUID id = p.getUniqueId();
        Session s = anvil.remove(id);
        if (s != null) s.done = true;
        ChatSession c = chat.remove(id);
        if (c != null && c.timeout != null) c.timeout.cancel();
    }

    private boolean openAnvil(Player p, TextPrompt prompt) {
        UUID id = p.getUniqueId();
        awaiting.put(id, new Session(p, prompt));
        try {
            p.openAnvil(null, true);
        } catch (RuntimeException | LinkageError e) {
            gui.plugin().log().debug("openAnvil nicht verfuegbar: " + e.getClass().getSimpleName());
        }
        awaiting.remove(id);
        return anvil.containsKey(id);
    }

    private void openChat(Player p, TextPrompt prompt) {
        UUID id = p.getUniqueId();
        ChatSession cs = new ChatSession(p, prompt);
        chat.put(id, cs);
        p.sendMessage(gui.config().text("text.chat.prompt", ph("label", prompt.label())));
        cs.timeout = gui.plugin().getServer().getScheduler().runTaskLater(gui.plugin(), () -> {
            if (chat.remove(id, cs)) {
                p.sendMessage(gui.config().text("text.chat.cancelled"));
                prompt.onCancel().run();
            }
        }, gui.config().chatTimeoutSeconds() * 20L);
    }

    void shutdown() {
        for (Session s : anvil.values()) {
            s.done = true;
            try {
                s.player.closeInventory();
            } catch (RuntimeException ignored) {
                // Spieler bereits getrennt
            }
        }
        anvil.clear();
        awaiting.clear();
        for (ChatSession c : chat.values()) if (c.timeout != null) c.timeout.cancel();
        chat.clear();
    }

    // ------------------------------------------------------------ Amboss-Events

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Session s = awaiting.get(p.getUniqueId());
        if (s == null || e.getInventory().getType() != InventoryType.ANVIL) return;
        awaiting.remove(p.getUniqueId());
        anvil.put(p.getUniqueId(), s);
        e.getInventory().setItem(0, s.inputItem());
    }

    @EventHandler
    public void onPrepare(PrepareAnvilEvent e) {
        List<HumanEntity> viewers = e.getViewers();
        if (viewers.isEmpty() || !(viewers.get(0) instanceof Player p)) return;
        Session s = anvil.get(p.getUniqueId());
        if (s == null) return;
        String text = AnvilReflect.renameText(e);
        s.text = text == null ? s.prompt.initial() : text;
        e.setResult(s.preview());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Session s = anvil.get(p.getUniqueId());
        if (s == null || e.getInventory().getType() != InventoryType.ANVIL) return;
        e.setCancelled(true);
        int raw = e.getRawSlot();
        if (raw == 0 || raw == 2) s.submit();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (anvil.containsKey(p.getUniqueId()) && e.getInventory().getType() == InventoryType.ANVIL) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Session s = anvil.get(p.getUniqueId());
        if (s == null || e.getInventory().getType() != InventoryType.ANVIL) return;
        anvil.remove(p.getUniqueId());
        e.getInventory().clear(); // verhindert, dass das GUI-Papier beim Schliessen ins Inventar gelangt
        if (!s.done) {
            s.done = true;
            gui.plugin().getServer().getScheduler().runTask(gui.plugin(), () -> s.prompt.onCancel().run());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        awaiting.remove(id);
        Session s = anvil.remove(id);
        if (s != null) s.done = true;
        ChatSession c = chat.remove(id);
        if (c != null && c.timeout != null) c.timeout.cancel();
    }

    // -------------------------------------------------------------- Chat-Events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        ChatSession cs = chat.get(e.getPlayer().getUniqueId());
        if (cs == null) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        gui.plugin().runMain(() -> cs.handle(msg));
    }

    // ----------------------------------------------------------------- Sitzungen

    private final class Session {
        final Player player;
        final TextPrompt prompt;
        volatile String text;
        boolean done;
        int attempts;

        Session(Player player, TextPrompt prompt) {
            this.player = player;
            this.prompt = prompt;
            this.text = prompt.initial();
        }

        ItemStack inputItem() {
            return ItemBuilder.of(Material.PAPER).name(Component.text(prompt.initial())).lore(prompt.lore()).build();
        }

        ItemStack preview() {
            return ItemBuilder.of(Material.PAPER).name(Component.text(text)).lore(prompt.lore()).build();
        }

        void submit() {
            if (done) return;
            String t = text == null ? "" : text.trim();
            Optional<Component> err = prompt.validator().apply(t);
            if (err.isPresent()) {
                attempts++;
                player.sendMessage(err.get());
                gui.config().sound(player, "code-wrong");
                if (prompt.maxAttempts() > 0 && attempts >= prompt.maxAttempts()) {
                    done = true;
                    gui.plugin().getServer().getScheduler().runTask(gui.plugin(), () -> {
                        player.closeInventory();
                        prompt.onLocked().run();
                    });
                }
                return;
            }
            done = true;
            gui.plugin().getServer().getScheduler().runTask(gui.plugin(), () -> {
                player.closeInventory();
                prompt.onSubmit().accept(t);
            });
        }
    }

    private final class ChatSession {
        final Player player;
        final TextPrompt prompt;
        BukkitTask timeout;
        int attempts;

        ChatSession(Player player, TextPrompt prompt) {
            this.player = player;
            this.prompt = prompt;
        }

        void handle(String msg) {
            UUID id = player.getUniqueId();
            if (chat.get(id) != this) return;
            if (msg.equalsIgnoreCase("abbrechen") || msg.equalsIgnoreCase("cancel")) {
                chat.remove(id);
                if (timeout != null) timeout.cancel();
                player.sendMessage(gui.config().text("text.chat.cancelled"));
                prompt.onCancel().run();
                return;
            }
            Optional<Component> err = prompt.validator().apply(msg);
            if (err.isPresent()) {
                attempts++;
                player.sendMessage(err.get());
                gui.config().sound(player, "code-wrong");
                if (prompt.maxAttempts() > 0 && attempts >= prompt.maxAttempts()) {
                    chat.remove(id);
                    if (timeout != null) timeout.cancel();
                    prompt.onLocked().run();
                }
                return;
            }
            chat.remove(id);
            if (timeout != null) timeout.cancel();
            prompt.onSubmit().accept(msg);
        }
    }
}
