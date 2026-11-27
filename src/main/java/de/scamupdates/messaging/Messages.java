package de.scamupdates.messaging;

import de.scamupdates.config.ConfigManager;
import de.scamupdates.logging.ScamLog;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Rendert alle Chat-Nachrichten aus der Config (MiniMessage) und baut klickbare Buttons. */
public final class Messages {
    private final ConfigManager config;
    private final ScamLog log;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();

    public Messages(ConfigManager config, ScamLog log) {
        this.config = config;
        this.log = log;
    }

    public static TagResolver ph(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver phc(String name, Component value) {
        return Placeholder.component(name, value);
    }

    private TagResolver prefixResolver() {
        String prefix = config.string("messages.prefix", "[ScamUpdates] ");
        return Placeholder.parsed("prefix", prefix);
    }

    public Component render(String key, TagResolver... extra) {
        Object raw = config.message(key);
        List<String> lines = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) lines.add(String.valueOf(o));
        } else if (raw != null) {
            lines.add(String.valueOf(raw));
        } else {
            if (warnedMissing.add(key)) log.warn("Nachricht fehlt in der Config: messages." + key);
            lines.add("<red>[fehlende Nachricht: " + key + "]");
        }
        TagResolver all = TagResolver.resolver(prefixResolver(), TagResolver.resolver(extra));
        List<Component> comps = new ArrayList<>();
        for (String l : lines) comps.add(mm.deserialize(l, all));
        return Component.join(JoinConfiguration.newlines(), comps);
    }

    public void send(CommandSender to, String key, TagResolver... extra) {
        to.sendMessage(render(key, extra));
    }

    public String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    public Component button(String id, String command) {
        String text = config.string("buttons." + id + ".text", "[" + id + "]");
        String hover = config.string("buttons." + id + ".hover", "");
        Component c = mm.deserialize(text).clickEvent(ClickEvent.runCommand(command));
        if (!hover.isBlank()) c = c.hoverEvent(HoverEvent.showText(mm.deserialize(hover)));
        return c;
    }

    public Component row(Component... parts) {
        return Component.join(JoinConfiguration.separator(Component.text(" ")), parts);
    }

    public void playSound(CommandSender to, String soundKey) {
        if (!(to instanceof Player player)) return;
        String spec = config.string("sounds." + soundKey, "");
        if (spec == null || spec.isBlank()) return;
        try {
            String[] parts = spec.split(";");
            float volume = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 1f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 1f;
            player.playSound(Sound.sound(Key.key(parts[0].trim()), Sound.Source.MASTER, volume, pitch));
        } catch (RuntimeException e) {
            log.debug("Ungueltiger Sound 'sounds." + soundKey + "': " + spec);
        }
    }

    public Component mini(String text, TagResolver... resolvers) {
        return mm.deserialize(text, TagResolver.resolver(resolvers));
    }
}
