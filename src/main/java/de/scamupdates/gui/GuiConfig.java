package de.scamupdates.gui;

import de.scamupdates.ScamUpdates;
import de.scamupdates.config.ResourceYaml;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Zugriff auf gui.yml: Titel, Texte, Items (Material, Name, Lore, Textur), Sounds, Design. */
public final class GuiConfig {
    private final ScamUpdates plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private volatile YamlConfiguration cfg = new YamlConfiguration();
    private volatile YamlConfiguration defaults = new YamlConfiguration();

    public GuiConfig(ScamUpdates plugin) { this.plugin = plugin; }

    public void load() {
        ResourceYaml.Loaded l = ResourceYaml.load(plugin, "gui.yml", plugin.log());
        this.cfg = l.cfg();
        this.defaults = l.defaults();
        if (l.usedDefaults()) plugin.log().error("gui.yml: " + l.error(), null);
    }

    private String str(String path, String fallback) {
        String v = cfg.getString(path);
        if (v == null) v = defaults.getString(path);
        return v == null ? fallback : v;
    }

    private List<String> strings(String path) {
        if (cfg.isList(path)) return cfg.getStringList(path);
        return defaults.getStringList(path);
    }

    private int num(String path, int fallback) {
        return cfg.isInt(path) ? cfg.getInt(path) : defaults.getInt(path, fallback);
    }

    private boolean flag(String path, boolean fallback) {
        return cfg.isBoolean(path) ? cfg.getBoolean(path) : defaults.getBoolean(path, fallback);
    }

    public boolean enabled() { return flag("general.enabled", true); }
    public boolean animations() { return flag("general.animations", true); }
    public int refreshTicks() { return Math.max(2, num("general.refresh-ticks", 10)); }
    public int clickCooldownMs() { return Math.max(0, num("general.click-cooldown-ms", 150)); }
    public int chatTimeoutSeconds() { return Math.max(5, num("general.chat-input-timeout-seconds", 60)); }
    public String inputMethod() { return str("general.input-method", "anvil").equalsIgnoreCase("chat") ? "chat" : "anvil"; }

    private TagResolver all(TagResolver... extra) {
        return TagResolver.resolver(Placeholder.parsed("prefix", plugin.config().string("messages.prefix", "")), TagResolver.resolver(extra));
    }

    public Component text(String path, TagResolver... r) {
        return mm.deserialize(str(path, "<red>[gui.yml: " + path + "]"), all(r));
    }

    public Component title(String key, TagResolver... r) { return text("titles." + key, r); }

    public List<Component> lore(String path, TagResolver... r) {
        List<Component> out = new ArrayList<>();
        for (String line : strings(path)) out.add(mm.deserialize(line, all(r)));
        return out;
    }

    public String label(String key) { return str("design-labels." + key, key); }

    public Material material(String path, Material fallback) {
        Material m = Material.matchMaterial(str(path, ""));
        return m == null ? fallback : m;
    }

    public Material design(String key, Material fallback) { return material("design." + key, fallback); }

    /** Item laut gui.yml (items.&lt;id&gt;.material/name/lore/texture). */
    public ItemBuilder item(String id, Material fallback, TagResolver... r) {
        String base = "items." + id;
        ItemBuilder b = ItemBuilder.of(material(base + ".material", fallback));
        b.name(text(base + ".name", r));
        b.lore(lore(base + ".lore", r));
        String tex = str(base + ".texture", "");
        if (!tex.isBlank()) b.texture(tex);
        return b;
    }

    public void sound(Player p, String key) {
        String spec = str("sounds." + key, "");
        if (spec.isBlank()) return;
        try {
            String[] parts = spec.split(";");
            float vol = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 1f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 1f;
            p.playSound(Sound.sound(Key.key(parts[0].trim()), Sound.Source.MASTER, vol, pitch));
        } catch (RuntimeException e) {
            plugin.log().debug("Ungueltiger GUI-Sound '" + key + "': " + spec);
        }
    }
}
