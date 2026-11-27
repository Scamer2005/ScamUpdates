package de.scamupdates.gui;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Beschreibung einer Texteingabe (Amboss oder Chat).
 *
 * @param validator   liefert eine Fehlermeldung oder leer, wenn die Eingabe gueltig ist
 * @param maxAttempts 0 = unbegrenzt; sonst wird nach so vielen ungueltigen Eingaben onLocked aufgerufen
 */
public record TextPrompt(String label, String initial, List<Component> lore,
                         Function<String, Optional<Component>> validator, Consumer<String> onSubmit,
                         Runnable onCancel, int maxAttempts, Runnable onLocked) {
}
