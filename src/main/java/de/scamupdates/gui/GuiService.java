package de.scamupdates.gui;

import de.scamupdates.ScamUpdates;
import de.scamupdates.compat.ServerVersion;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.messaging.Messages;
import de.scamupdates.net.PaperDownloads;
import de.scamupdates.security.ConfirmationManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static de.scamupdates.messaging.Messages.ph;

/** Einstiegspunkt der grafischen Oberflaeche. */
public final class GuiService {
    private final ScamUpdates plugin;
    private final GuiConfig config;
    private final MenuManager manager;
    private final InputManager input;
    private final Map<UUID, Boolean> detailed = new ConcurrentHashMap<>();

    public GuiService(ScamUpdates plugin) {
        this.plugin = plugin;
        this.config = new GuiConfig(plugin);
        this.config.load();
        this.manager = new MenuManager(this);
        this.input = new InputManager(this);
        plugin.getServer().getPluginManager().registerEvents(manager, plugin);
        plugin.getServer().getPluginManager().registerEvents(input, plugin);
        manager.startTicker();
    }

    ScamUpdates plugin() { return plugin; }
    GuiConfig config() { return config; }
    MenuManager manager() { return manager; }

    public boolean enabled() { return config.enabled(); }

    public void reload() { config.load(); }

    public void shutdown() {
        manager.closeAll();
        input.shutdown();
    }

    public void openMain(Player p) { new MainMenu(this, p).open(); }

    public void openCheck(Player p) { new CheckMenu(this, p).open(); }

    public void openHistory(Player p) { new HistoryMenu(this, p, HistoryStore.Sort.NEWEST).open(); }

    public void ask(Player p, TextPrompt prompt) { input.ask(p, prompt); }

    /** Vor jedem Klick: Plugin muss betriebsbereit sein, Doppelklicks werden gedrosselt. */
    boolean allow(Player p) {
        if (!plugin.state().isOperational()) {
            p.closeInventory();
            p.sendMessage(plugin.messages().render("plugin-disabled", ph("state", plugin.state().state())));
            return false;
        }
        return manager.cooldownOk(p.getUniqueId());
    }

    boolean detailed(Player p) { return detailed.getOrDefault(p.getUniqueId(), false); }

    void setDetailed(Player p, boolean d) { detailed.put(p.getUniqueId(), d); }

    // -------------------------------------------------------- Code-Bestaetigung

    /** Einmalcode im Amboss-Fenster (bzw. Chat). Der Code ist spieler-, aktions- und zeitgebunden (ConfirmationManager). */
    public void codeConfirm(Player p, ConfirmationManager.Pending pending, Component question) {
        ConfirmationManager cm = plugin.confirmations();
        UUID id = p.getUniqueId();
        int max = plugin.config().confirmMaxAttempts();
        AtomicInteger used = new AtomicInteger();
        p.sendMessage(plugin.messages().render("confirm.code-info", ph("code", pending.token()),
                ph("seconds", plugin.config().confirmTtlSeconds())));
        String initial = PlainTextComponentSerializer.plainText().serialize(config.text("text.code.input-name"));
        List<Component> lore = config.lore("items.code-paper.lore", ph("code", pending.token()), Messages.phc("question", question));
        TextPrompt prompt = new TextPrompt("Bestaetigungscode", initial, lore,
                in -> {
                    if (in.equalsIgnoreCase(pending.token())) return Optional.empty();
                    return Optional.of(plugin.messages().render("confirm.code-wrong", ph("left", Math.max(0, max - used.incrementAndGet()))));
                },
                in -> plugin.command().confirmToken(p, in),
                () -> {
                    cm.cancel(pending.token(), id);
                    p.sendMessage(config.text("text.code.cancelled"));
                },
                max,
                () -> {
                    cm.cancel(pending.token(), id);
                    p.sendMessage(plugin.messages().render("confirm.code-locked"));
                });
        ask(p, prompt);
    }

    // ------------------------------------------------------------ Hilfsfunktionen

    String compatLabel() {
        return config.label("compat-" + plugin.compat().statusKey());
    }

    /** Kurzer Hinweis zur Paper-Pruefung. Neuere Minecraft-Versionen ausserhalb des Supportbereichs werden nicht beworben. */
    String paperHint(PaperDownloads.PaperStatus ps) {
        if (ps == null) return "noch nicht geprueft";
        if (ps.failed()) return "Pruefung fehlgeschlagen: " + ps.error();
        if (ps.newerBuildAvailable()) return "Neuerer Build verfuegbar";
        String hint = "Paper ist aktuell";
        boolean supportedNewer = ps.newerMinecraftAvailable()
                && ServerVersion.parse(ps.newestMinecraft()).map(v -> !v.isNewerThan("1.21.999")).orElse(false);
        return supportedNewer ? hint + "; neuere Minecraft-Version " + ps.newestMinecraft() + " verfuegbar" : hint;
    }

    static String duration(long millis) {
        long s = Math.max(0, millis / 1000);
        return s < 60 ? s + "s" : (s / 60) + "m " + String.format("%02d", s % 60) + "s";
    }

    ItemBuilder item(String id, Material fallback, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        return config.item(id, fallback, r);
    }
}
