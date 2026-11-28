package de.scamupdates.gui;

import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;

import java.lang.reflect.Method;

/** Liest den Umbenennungstext versionsunabhaengig (AnvilView ab 1.21, AnvilInventory davor) per Reflection. */
final class AnvilReflect {
    private AnvilReflect() {}

    private static boolean resolved;
    private static Method getViewMethod;
    private static Method viewMethod;
    private static Method inventoryMethod;

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> anvilView = Class.forName("org.bukkit.inventory.view.AnvilView");
            getViewMethod = PrepareAnvilEvent.class.getMethod("getView");
            viewMethod = anvilView.getMethod("getRenameText");
        } catch (ReflectiveOperationException | LinkageError e) {
            getViewMethod = null;
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
        if (viewMethod != null && getViewMethod != null) {
            try {
                Object view = getViewMethod.invoke(e);
                if (view != null) {
                    Object r = viewMethod.invoke(view);
                    return r == null ? null : r.toString();
                }
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
