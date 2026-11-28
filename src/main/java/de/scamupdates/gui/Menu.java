package de.scamupdates.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Basis aller Menues. Menues sind nur Ansichten: laufende Vorgaenge leben in UpdateService/OperationRegistry,
 * deshalb ueberlebt jeder Vorgang das Schliessen und beim erneuten Oeffnen wird der aktuelle Stand gebaut.
 * Alle Methoden laufen auf dem Hauptthread. Klicks werden immer abgebrochen (kein Item kann entnommen werden).
 */
public abstract class Menu implements InventoryHolder {
    protected final GuiService gui;
    protected final Player viewer;
    private Inventory inventory;
    private final Map<Integer, Button> buttons = new HashMap<>();
    protected long ticks;

    protected Menu(GuiService gui, Player viewer) {
        this.gui = gui;
        this.viewer = viewer;
    }

    protected abstract int rows();

    protected abstract Component title();

    /** Fuellt das Inventar ueber set()/frame(). */
    protected abstract void build();

    protected void onTick() {}

    /** true, wenn sich der Inhalt geaendert hat und neu gebaut werden soll. */
    protected boolean needsRender() { return false; }

    protected void onClosed() {}

    public final void open() {
        inventory = Bukkit.createInventory(this, rows() * 9, title());
        render();
        gui.manager().show(viewer, this, inventory);
        gui.config().sound(viewer, "open");
    }

    public final void render() {
        if (inventory == null) return;
        buttons.clear();
        inventory.clear();
        build();
    }

    final void tick() {
        ticks++;
        onTick();
        if (needsRender()) render();
    }

    final void closed() { onClosed(); }

    final Inventory inventory() { return inventory; }

    protected final boolean isViewing() {
        return viewer != null && gui.manager().isOpen(viewer, this);
    }

    protected final void set(int slot, Button b) {
        if (slot < 0 || slot >= rows() * 9) return;
        buttons.put(slot, b);
        inventory.setItem(slot, b.item());
    }

    protected final void set(int slot, ItemStack display) { set(slot, Button.display(display)); }

    /** Dekorativer Rahmen und Hintergrund. */
    protected final void frame() {
        Material border = gui.config().design("border", Material.ORANGE_STAINED_GLASS_PANE);
        Material filler = gui.config().design("filler", Material.GRAY_STAINED_GLASS_PANE);
        ItemStack b = ItemBuilder.of(border).name(Component.space()).build();
        ItemStack f = ItemBuilder.of(filler).name(Component.space()).build();
        int size = rows() * 9;
        for (int i = 0; i < size; i++) {
            int row = i / 9, col = i % 9;
            boolean edge = row == 0 || row == rows() - 1 || col == 0 || col == 8;
            inventory.setItem(i, edge ? b : f);
        }
    }

    protected final void backButton(Runnable action) {
        set(Slots.BACK, Button.of(gui.config().item("nav-back", Material.ARROW).build(), c -> action.run()));
    }

    protected final void closeButton() {
        set(Slots.CLOSE, Button.of(gui.config().item("nav-close", Material.BARRIER).build(), c -> viewer.closeInventory()));
    }

    final void click(InventoryClickEvent e) {
        Button b = buttons.get(e.getRawSlot());
        if (b == null || b.handler() == null) return;
        if (!gui.allow(viewer)) return;
        if (b.perm() != null && !b.perm().allows(n -> viewer.hasPermission(n))) {
            gui.config().sound(viewer, "error");
            viewer.sendMessage(gui.plugin().messages().render("gui.no-permission-item"));
            return;
        }
        gui.config().sound(viewer, "click");
        try {
            b.handler().click(new Button.Click(viewer, e.getClick()));
        } catch (RuntimeException ex) {
            gui.plugin().log().error("GUI-Aktion fehlgeschlagen (" + getClass().getSimpleName() + ")", ex);
            viewer.sendMessage(gui.plugin().messages().render("error-generic",
                    de.scamupdates.messaging.Messages.ph("error", ex.getClass().getSimpleName())));
        }
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
