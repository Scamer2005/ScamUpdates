package de.scamupdates.restart;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Lokaler Neustart ueber den Minecraft-Befehl /restart (Spigot/Paper), wenn Crafty deaktiviert ist.
 * /restart startet nur dann neu, wenn in der spigot.yml ein vorhandenes restart-script eingetragen ist;
 * andernfalls wird der Server lediglich beendet. Das wird hier geprueft, statt einen Neustart vorzutaeuschen.
 */
public final class LocalRestart {

    public record Status(boolean ok, String detail, String script) {}

    private final Path serverRoot;
    private final String command;
    private final BooleanSupplier commandExists;
    private final Predicate<String> dispatcher;

    public LocalRestart(Path serverRoot, String command) {
        this(serverRoot, command,
                () -> {
                    String cmd = command == null || command.isBlank() ? "restart" : command.trim();
                    Command c = Bukkit.getCommandMap().getCommand(cmd);
                    return c != null;
                },
                cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
    }

    public LocalRestart(Path serverRoot, String command, BooleanSupplier commandExists, Predicate<String> dispatcher) {
        this.serverRoot = serverRoot;
        this.command = command == null || command.isBlank() ? "restart" : command.trim();
        this.commandExists = Objects.requireNonNull(commandExists, "commandExists");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /** Hauptthread (Befehlsregister). */
    public Status evaluate() {
        if (!commandExists.getAsBoolean()) {
            return new Status(false, "Der Befehl /" + command + " existiert auf diesem Server nicht.", "");
        }
        return evaluateScript(serverRoot);
    }

    /** Dateiseitige Pruefung des restart-script (ohne Bukkit, testbar). */
    public static Status evaluateScript(Path serverRoot) {
        YamlConfiguration spigot = YamlConfiguration.loadConfiguration(serverRoot.resolve("spigot.yml").toFile());
        String script = spigot.getString("settings.restart-script", "./start.sh");
        String first = script == null ? "" : script.trim().split("\\s+")[0];
        if (first.isEmpty()) {
            return new Status(false, "In der spigot.yml ist kein restart-script eingetragen. /restart wuerde den Server nur beenden.", "");
        }
        Path p = serverRoot.resolve(first).normalize();
        if (!Files.isRegularFile(p)) {
            return new Status(false, "Das Restart-Skript '" + script + "' (spigot.yml: settings.restart-script) existiert nicht. "
                    + "/restart wuerde den Server nur beenden, nicht neu starten.", script);
        }
        return new Status(true, "Restart-Skript gefunden: " + script, script);
    }

    /** Hauptthread: prueft erneut und fuehrt /restart nur aus, wenn der Mechanismus funktionsfaehig ist. */
    public Status trigger() {
        Status st = evaluate();
        if (!st.ok()) return st;
        boolean dispatched = dispatcher.test(command);
        if (!dispatched) return new Status(false, "/" + command + " wurde vom Server nicht ausgefuehrt.", st.script());
        return st;
    }
}
