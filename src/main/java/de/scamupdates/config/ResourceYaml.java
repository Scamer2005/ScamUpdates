package de.scamupdates.config;

import de.scamupdates.logging.ScamLog;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Laedt eine zusaetzliche YAML-Datei (z. B. gui.yml) samt Migration; bei Fehlern gelten die eingebauten Standardwerte. */
public final class ResourceYaml {
    private ResourceYaml() {}

    public record Loaded(YamlConfiguration cfg, YamlConfiguration defaults, String error) {
        public boolean usedDefaults() { return error != null; }
    }

    public static Loaded load(JavaPlugin plugin, String resourceName, ScamLog log) {
        YamlConfiguration def = new YamlConfiguration();
        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) return new Loaded(new YamlConfiguration(), new YamlConfiguration(), resourceName + " fehlt in der JAR");
            def.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            return new Loaded(new YamlConfiguration(), def, "Interne " + resourceName + " nicht lesbar: " + e.getMessage());
        }
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) plugin.saveResource(resourceName, false);
        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            return new Loaded(def, def, resourceName + " ist ungueltig (" + firstLine(e.getMessage()) + ") - Standardwerte aktiv");
        }
        int userV = user.getInt("config-version", 0), defV = def.getInt("config-version", 1);
        if (userV > defV) {
            return new Loaded(def, def, resourceName + ": config-version " + userV + " ist neuer als unterstuetzt - Standardwerte aktiv");
        }
        ConfigMigrator.Result r = ConfigMigrator.migrate(user, def);
        if (r.changed()) {
            try {
                Path bak = file.toPath().resolveSibling(resourceName + ".bak-v" + r.fromVersion());
                Files.copy(file.toPath(), bak, StandardCopyOption.REPLACE_EXISTING);
                user.save(file);
                log.info(resourceName + " migriert (v" + r.fromVersion() + " -> v" + r.toVersion() + "), "
                        + r.addedKeys().size() + " Einstellung(en) ergaenzt. Sicherung: " + bak.getFileName());
            } catch (IOException e) {
                log.error(resourceName + " konnte nach der Migration nicht gespeichert werden", e);
            }
        }
        return new Loaded(user, def, null);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
