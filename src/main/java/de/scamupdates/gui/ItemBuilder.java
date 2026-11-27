package de.scamupdates.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Baut Menue-Items versionssicher: Namen/Lore als Components, Flags ueber valueOf (fehlende Konstanten werden ignoriert). */
public final class ItemBuilder {
    private static final String[] HIDE_FLAGS = {"HIDE_ATTRIBUTES", "HIDE_ENCHANTS", "HIDE_ADDITIONAL_TOOLTIP",
            "HIDE_POTION_EFFECTS", "HIDE_DESTROYS", "HIDE_PLACED_ON", "HIDE_UNBREAKABLE", "HIDE_DYE", "HIDE_ARMOR_TRIM"};

    private final Material material;
    private int amount = 1;
    private Component name;
    private final List<Component> lore = new ArrayList<>();
    private String texture;

    private ItemBuilder(Material material) { this.material = material; }

    public static ItemBuilder of(Material m) { return new ItemBuilder(m == null ? Material.STONE : m); }

    public ItemBuilder name(Component c) { this.name = c; return this; }

    public ItemBuilder lore(List<Component> l) { lore.clear(); lore.addAll(l); return this; }

    public ItemBuilder addLore(List<Component> l) { lore.addAll(l); return this; }

    public ItemBuilder addLore(Component c) { lore.add(c); return this; }

    public ItemBuilder amount(int a) { this.amount = Math.max(1, Math.min(64, a)); return this; }

    public ItemBuilder texture(String t) { this.texture = t; return this; }

    public ItemStack build() {
        Material m = material;
        if (texture != null && !texture.isBlank() && m != Material.PLAYER_HEAD) m = Material.PLAYER_HEAD;
        ItemStack is = new ItemStack(m, amount);
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return is;
        if (name != null) meta.displayName(noItalic(name));
        if (!lore.isEmpty()) {
            List<Component> l = new ArrayList<>();
            for (Component c : lore) l.add(noItalic(c));
            meta.lore(l);
        }
        for (String f : HIDE_FLAGS) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(f));
            } catch (IllegalArgumentException ignored) {
                // Flag existiert in dieser Minecraft-Version nicht
            }
        }
        if (texture != null && !texture.isBlank() && meta instanceof SkullMeta skull) applyTexture(skull, texture);
        is.setItemMeta(meta);
        return is;
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static void applyTexture(SkullMeta meta, String texture) {
        String url = resolveUrl(texture);
        if (url == null) return;
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)), "scamupdates");
            PlayerTextures tex = profile.getTextures();
            tex.setSkin(new URL(url));
            profile.setTextures(tex);
            meta.setOwnerProfile(profile);
        } catch (MalformedURLException | RuntimeException e) {
            // Ungueltige Textur: normaler Kopf bleibt
        }
    }

    /** Akzeptiert eine textures.minecraft.net-URL oder einen Base64-Texturwert; fremde Hosts werden abgelehnt. */
    static String resolveUrl(String texture) {
        String t = texture.trim();
        if (isMojangUrl(t)) return t;
        try {
            String json = new String(Base64.getDecoder().decode(t), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String url = o.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
            return isMojangUrl(url) ? url : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isMojangUrl(String u) {
        return u.startsWith("https://textures.minecraft.net/texture/") || u.startsWith("http://textures.minecraft.net/texture/");
    }
}
