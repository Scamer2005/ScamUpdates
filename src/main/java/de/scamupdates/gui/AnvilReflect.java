package de.scamupdates.gui;

import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;

import java.lang.reflect.Method;

/** Liest den Umbenennungstext versionsunabhaengig (AnvilView ab 1.21, AnvilInventory davor) per Reflection. */
final class AnvilReflect {
    private AnvilReflect() {}

    private static boolean resolved;
    private static Method viewMethod;
    private static Method inventoryMethod;

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            viewMethod = Class.forName("org.bukkit.inventory.view.AnvilView").getMethod("getRenameText");
        } catch (ReflectiveOperationException | LinkageError e) {
            viewMethod = null;
        }
        try {
            inventoryMethod = AnvilInventory.class.getMethod("getRenameText");
        } catch (ReflectiveOperationException | LinkageError e) {
            inventoryMethod = null;
        }
    }

    /** @return der Text, oder null, wenn noch nichts umbenannt wurde bzw. nicht lesbar */
    static String renameText(PrepareAnvilEvent e) {
        resolve();
        if (viewMethod != null) {
            try {
                Object r = viewMethod.invoke(e.getView());
                return r == null ? null : r.toString();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                // weiter mit Fallback
            }
        }
        if (inventoryMethod != null) {
            try {
                Object r = inventoryMethod.invoke(e.getInventory());
                return r == null ? null : r.toString();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                return null;
            }
        }
        return null;
    }
}
