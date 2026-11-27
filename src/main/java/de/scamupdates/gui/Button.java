package de.scamupdates.gui;

import de.scamupdates.security.Perm;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/** Ein Menue-Feld. perm wird bei JEDEM Klick serverseitig erneut geprueft. */
public record Button(ItemStack item, Handler handler, Perm perm) {

    @FunctionalInterface
    public interface Handler {
        void click(Click click);
    }

    public record Click(Player player, ClickType type) {
        public boolean isRight() { return type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT; }
    }

    public static Button of(ItemStack item, Handler handler) { return new Button(item, handler, null); }

    public static Button of(ItemStack item, Perm perm, Handler handler) { return new Button(item, handler, perm); }

    public static Button display(ItemStack item) { return new Button(item, null, null); }
}
