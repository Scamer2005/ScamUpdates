package de.scamupdates.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Ergaenzt fehlende Schluessel (samt Kommentaren) aus der mitgelieferten Default-Config,
 * ohne vorhandene Werte oder Kommentare des Benutzers zu ueberschreiben.
 */
public final class ConfigMigrator {
    private ConfigMigrator() {}

    public record Result(boolean changed, int fromVersion, int toVersion, List<String> addedKeys) {}

    public static Result migrate(YamlConfiguration user, YamlConfiguration defaults) {
        int target = defaults.getInt("config-version", 1);
        int from = user.getInt("config-version", 0);
        List<String> added = new ArrayList<>();

        // Beim Versionswechsel werden die KOMMENTARE bestehender Schluessel durch die aktuellen (kompakten)
        // Standardkommentare ersetzt. Werte werden nie veraendert; eigene Kommentare gehen dabei verloren
        // (die Sicherung config.yml.bak-v<alt> enthaelt sie weiterhin).
        if (from < target) {
            for (String key : defaults.getKeys(true)) {
                if (user.isSet(key) || user.isConfigurationSection(key)) {
                    user.setComments(key, defaults.getComments(key));
                    user.setInlineComments(key, defaults.getInlineComments(key));
                }
            }
        }

        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                if (!user.isSet(key)) {
                    user.createSection(key);
                    user.setComments(key, defaults.getComments(key));
                    added.add(key);
                }
                continue;
            }
            if (!user.isSet(key)) {
                user.set(key, defaults.get(key));
                user.setComments(key, defaults.getComments(key));
                added.add(key);
            }
        }
        boolean versionChanged = from != target;
        user.set("config-version", target);
        return new Result(versionChanged || !added.isEmpty(), from, target, added);
    }
}
