package de.scamupdates.commands;

import de.scamupdates.ScamUpdates;
import de.scamupdates.backup.BackupManager;
import de.scamupdates.history.HistoryEntry;
import de.scamupdates.history.HistoryStore;
import de.scamupdates.match.MatchService;
import de.scamupdates.match.MatchStore;
import de.scamupdates.messaging.Messages;
import de.scamupdates.model.PluginInfo;
import de.scamupdates.model.UpdateCandidate;
import de.scamupdates.restart.RestartService;
import de.scamupdates.rollback.RollbackService;
import de.scamupdates.scan.PluginScanner;
import de.scamupdates.security.ConfirmationManager;
import de.scamupdates.security.Perm;
import de.scamupdates.sources.UpdateSource;
import de.scamupdates.state.StateManager;
import de.scamupdates.update.UpdateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.text.DateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static de.scamupdates.messaging.Messages.ph;

public final class ScamCommand implements TabExecutor {
    private static final UUID CONSOLE = new UUID(0L, 0L);

    private final ScamUpdates plugin;
    private final Messages msg;

    public ScamCommand(ScamUpdates plugin) {
        this.plugin = plugin;
        this.msg = plugin.messages();
    }

    // ------------------------------------------------------------------ Eintritt

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) && !(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("ScamUpdates kann nur von Spielern und der Konsole genutzt werden.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!CommandPolicy.SUBCOMMANDS.contains(sub)) {
            msg.send(sender, "usage", ph("usage", "/scamupdates help"));
            return true;
        }
        Perm required = CommandPolicy.required(args);
        if (required != null && !required.allows(n -> sender.hasPermission(n))) {
            msg.send(sender, "no-permission");
            msg.playSound(sender, "error");
            return true;
        }
        if (!CommandPolicy.ALLOWED_WHEN_DISABLED.contains(sub) && !plugin.state().isOperational()) {
            if (plugin.state().state().isDisabled()) plugin.notifier().sendStateError(sender);
            else msg.send(sender, "plugin-disabled", ph("state", plugin.state().state()));
            return true;
        }
        if (args.length == 0 && sender instanceof Player gp && plugin.state().isOperational()) {
            if (plugin.gui().enabled()) plugin.gui().openMain(gp);
            else msg.send(sender, "gui.disabled");
            return true;
        }
        try {
            switch (sub) {
                case "help" -> msg.send(sender, "help");
                case "history" -> history(sender);
                case "status" -> status(sender);
                case "check" -> check(sender);
                case "download" -> download(sender);
                case "list" -> list(sender, args);
                case "restore" -> restore(sender, args);
                case "backup" -> backup(sender, args);
                case "restart" -> restart(sender);
                case "config" -> config(sender, args);
                case "sources" -> sources(sender);
                case "exclude" -> exclude(sender, args);
                case "include" -> include(sender, args);
                case "match" -> match(sender, args);
                case "pick" -> pick(sender, args);
                case "confirm" -> confirm(sender, args);
                case "cancel" -> cancel(sender, args);
                case "later" -> msg.send(sender, "later");
                default -> msg.send(sender, "usage", ph("usage", "/scamupdates help"));
            }
        } catch (RuntimeException e) {
            plugin.log().error("Befehl fehlgeschlagen: /" + label + " " + String.join(" ", args), e);
            msg.send(sender, "error-generic", ph("error", e.getClass().getSimpleName()));
        }
        return true;
    }

    private static UUID idOf(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId() : CONSOLE;
    }

    // ------------------------------------------------------------- Bestaetigung

    private void confirmOrRun(CommandSender sender, String action, Perm perm, String questionKey,
                              TagResolver[] qres, Runnable run) {
        if (!plugin.config().confirmationEnabledFor(action)) {
            plugin.log().warn("Bestaetigung fuer '" + action + "' ist in der Config deaktiviert; Aktion von " + sender.getName() + " wird sofort ausgefuehrt.");
            run.run();
            return;
        }
        ConfirmationManager.Pending p;
        try {
            p = plugin.confirmations().create(idOf(sender), action, perm, () -> {
                plugin.log().info("Bestaetigt von " + sender.getName() + ": " + action);
                run.run();
            });
        } catch (ConfirmationManager.TooManyPendingException e) {
            msg.send(sender, "confirm.too-many");
            return;
        }
        Component question = msg.render("confirm.question." + questionKey, qres);
        if (sender instanceof Player player && plugin.config().confirmByAnvil() && plugin.gui() != null) {
            plugin.gui().codeConfirm(player, p, question);
            return;
        }
        Component prompt = msg.render("confirm.prompt", Messages.phc("question", question),
                ph("seconds", plugin.config().confirmTtlSeconds()));
        Component buttons = msg.row(msg.button("confirm", "/scamupdates confirm " + p.token()),
                msg.button("cancel", "/scamupdates cancel " + p.token()));
        sender.sendMessage(Component.join(JoinConfiguration.newlines(), prompt, buttons));
        msg.playSound(sender, "confirm");
    }

    private void confirm(CommandSender sender, String[] args) {
        if (args.length < 2) { msg.send(sender, "usage", ph("usage", "/scamupdates confirm [Token]")); return; }
        confirmToken(sender, args[1]);
    }

    /** Loest eine Bestaetigung ein (Chat-Token oder Amboss-Code); Berechtigung, Spielerbindung und Ablauf werden hier geprueft. */
    public void confirmToken(CommandSender sender, String token) {
        if (!plugin.state().isOperational()) {
            msg.send(sender, "plugin-disabled", ph("state", plugin.state().state()));
            return;
        }
        ConfirmationManager.Outcome o = plugin.confirmations().confirm(token, idOf(sender),
                perm -> perm.allows(n -> sender.hasPermission(n)));
        switch (o.result()) {
            case INVALID -> msg.send(sender, "confirm.invalid");
            case NOT_OWNER -> {
                plugin.log().warn(sender.getName() + " hat versucht, eine fremde Bestaetigung zu verwenden.");
                msg.send(sender, "confirm.not-owner");
            }
            case EXPIRED -> msg.send(sender, "confirm.expired");
            case NO_PERMISSION -> msg.send(sender, "no-permission");
            case OK -> {
                try {
                    o.pending().run().run();
                } catch (RuntimeException e) {
                    plugin.log().error("Bestaetigte Aktion fehlgeschlagen: " + o.pending().action(), e);
                    msg.send(sender, "error-generic", ph("error", e.getClass().getSimpleName()));
                }
            }
        }
    }

    private void cancel(CommandSender sender, String[] args) {
        int n;
        if (args.length >= 2) n = plugin.confirmations().cancel(args[1], idOf(sender)) ? 1 : 0;
        else n = plugin.confirmations().cancelAll(idOf(sender));
        if (n == 0 && args.length >= 2) msg.send(sender, "confirm.invalid");
        else msg.send(sender, "confirm.cancelled", ph("count", n));
    }

    private static Throwable root(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    private void reportFailure(CommandSender sender, Throwable raw) {
        Throwable t = root(raw);
        if (t instanceof UpdateService.BusyException b) msg.send(sender, "busy", ph("operation", String.valueOf(b.getMessage())));
        else if (t instanceof StateManager.OfflineException oe) msg.send(sender, "offline.action-blocked", ph("reason", plugin.log().redact(String.valueOf(oe.getMessage()))));
        else if (t instanceof IllegalStateException) msg.send(sender, "plugin-disabled", ph("state", plugin.state().state()));
        else {
            plugin.log().error("Aktion fehlgeschlagen", t);
            msg.send(sender, "error-generic", ph("error", plugin.log().redact(String.valueOf(t.getMessage()))));
        }
        msg.playSound(sender, "error");
    }

    // ------------------------------------------------------------------- check

    private void check(CommandSender sender) {
        long wait = plugin.updates().secondsUntilNextManualCheck();
        if (wait > 0) { msg.send(sender, "check.cooldown", ph("seconds", wait)); return; }
        runCheck(sender);
    }

    private void runCheck(CommandSender sender) {
        long wait = plugin.updates().secondsUntilNextManualCheck();
        if (wait > 0) { msg.send(sender, "check.cooldown", ph("seconds", wait)); return; }
        msg.send(sender, "check.started");
        plugin.updates().check(sender.getName()).whenComplete((snap, err) -> plugin.runMain(() -> {
            if (err != null) { reportFailure(sender, err); return; }
            int updates = snap.updates().size();
            int pending = plugin.matches().pendingChoices().size();
            msg.send(sender, "check.finished", ph("updates", updates), ph("errors", snap.count(UpdateService.Kind.ERROR)), ph("pending", pending));
            if (updates > 0 || pending > 0) sender.sendMessage(plugin.notifier().updateMessage(updates, pending));
            else msg.send(sender, "updates.none");
            msg.playSound(sender, updates > 0 ? "notify" : "success");
        }));
    }

    // ---------------------------------------------------------------- download

    private void download(CommandSender sender) {
        List<UpdateCandidate> list = plugin.updates().snapshot().updates();
        if (list.isEmpty()) { msg.send(sender, "download.none"); return; }
        if (!plugin.state().isOnline()) {
            plugin.io().submit(() -> {
                boolean online = plugin.connectivity().ensureOnlineOrRefresh();
                plugin.runMain(() -> {
                    if (!online) {
                        String reason = plugin.state().details().isEmpty() ? "Keine Internetverbindung" : plugin.state().details().get(0);
                        msg.send(sender, "offline.action-blocked", ph("reason", reason));
                        msg.playSound(sender, "error");
                        return;
                    }
                    confirmOrRun(sender, "download", Perm.DOWNLOAD, "download", new TagResolver[]{ph("count", list.size())},
                            () -> startDownload(sender));
                });
            });
            return;
        }
        confirmOrRun(sender, "download", Perm.DOWNLOAD, "download", new TagResolver[]{ph("count", list.size())},
                () -> startDownload(sender));
    }

    private void startDownload(CommandSender sender) {
        List<UpdateCandidate> list = plugin.updates().snapshot().updates();
        if (list.isEmpty()) { msg.send(sender, "download.none"); return; }
        plugin.log().info(sender.getName() + " startet den Download von " + list.size() + " Update(s).");
        msg.send(sender, "download.started", ph("count", list.size()));
        plugin.updates().download(list, item -> {
            if (item.ok()) msg.send(sender, "download.item-ok", ph("plugin", item.plugin()), ph("from", item.from()), ph("to", item.to()));
            else msg.send(sender, "download.item-failed", ph("plugin", item.plugin()), ph("error", item.error()));
        }, sender.getName()).whenComplete((report, err) -> plugin.runMain(() -> {
            if (err != null) { reportFailure(sender, err); return; }
            long ok = report.okCount(), failed = report.failedCount();
            plugin.updates().markPending(report.items().stream().filter(UpdateService.Item::ok).map(UpdateService.Item::plugin).toList());
            if (report.abortedAllOrNothing()) msg.send(sender, "download.aborted");
            else if (ok == 0) msg.send(sender, "download.failed-all");
            else if (failed == 0) msg.send(sender, "download.finished", ph("count", ok));
            else msg.send(sender, "download.partial", ph("ok", ok), ph("failed", failed));
            if (ok > 0 && !report.abortedAllOrNothing()) {
                if (plugin.restartAvailable()) sendRestartOffer(sender, (int) ok);
                else msg.send(sender, "download.no-restart-warning");
            }
            msg.playSound(sender, ok > 0 ? "success" : "error");
        }));
    }

    private void sendRestartOffer(CommandSender sender, int count) {
        sender.sendMessage(Component.join(JoinConfiguration.newlines(),
                msg.render("restart.offer", ph("count", count)),
                msg.row(msg.button("restart-now", "/scamupdates restart"), msg.button("later", "/scamupdates later"))));
    }

    // -------------------------------------------------------------------- list

    private void list(CommandSender sender, String[] args) {
        boolean onlyUpdates = false;
        int page = 1;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("updates")) onlyUpdates = true;
            else if (args[i].equalsIgnoreCase("all")) onlyUpdates = false;
            else {
                try { page = Math.max(1, Integer.parseInt(args[i])); }
                catch (NumberFormatException e) { msg.send(sender, "usage", ph("usage", "/scamupdates list [updates|all] [Seite]")); return; }
            }
        }
        List<UpdateService.PluginStatus> all = new ArrayList<>(plugin.updates().snapshot().statuses());
        if (onlyUpdates) all.removeIf(s -> s.kind() != UpdateService.Kind.UPDATE_AVAILABLE);
        all.sort(Comparator.comparing(s -> s.plugin().name().toLowerCase(Locale.ROOT)));
        if (all.isEmpty()) { msg.send(sender, plugin.updates().snapshot().time() == 0 ? "list.empty" : "updates.none"); return; }
        int size = plugin.config().pageSize();
        int pages = Math.max(1, (all.size() + size - 1) / size);
        page = Math.min(page, pages);
        List<Component> lines = new ArrayList<>();
        lines.add(msg.render("list.header", ph("title", onlyUpdates ? "Updates" : "Plugins"), ph("page", page), ph("pages", pages)));
        for (UpdateService.PluginStatus s : all.subList((page - 1) * size, Math.min(all.size(), page * size))) lines.add(renderStatus(s));
        sender.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
        sendPager(sender, "/scamupdates list " + (onlyUpdates ? "updates" : "all") + " ", page, pages);
    }

    private Component renderStatus(UpdateService.PluginStatus s) {
        String name = s.plugin().name();
        String src = s.project() == null ? "-" : s.project().sourceId();
        return switch (s.kind()) {
            case UPDATE_AVAILABLE -> s.release().downloadUrl() == null
                    ? msg.render("list.manual-download", ph("plugin", name), ph("from", s.plugin().version()), ph("to", s.release().version()), ph("url", s.project().url()))
                    : msg.render("list.update", ph("plugin", name), ph("from", s.plugin().version()), ph("to", s.release().version()),
                            ph("source", src), ph("channel", s.release().channel().name().toLowerCase(Locale.ROOT)));
            case UP_TO_DATE, NO_RELEASE -> msg.render("list.current", ph("plugin", name), ph("version", s.plugin().version()), ph("source", src));
            case EXCLUDED -> msg.render("list.excluded", ph("plugin", name));
            case UNMATCHED -> msg.render("list.unmatched", ph("plugin", name));
            case PENDING_MATCH -> msg.render("list.pending-match", ph("plugin", name));
            case PENDING_RESTART -> msg.render("list.pending-restart", ph("plugin", name));
            case INCOMPATIBLE -> msg.render("list.incompatible", ph("plugin", name), ph("to", s.release().version()));
            case ERROR -> msg.render("list.error", ph("plugin", name), ph("error", String.valueOf(s.error())));
        };
    }

    private void sendPager(CommandSender sender, String base, int page, int pages) {
        if (pages <= 1) return;
        Component prev = page > 1 ? msg.button("prev", base + (page - 1)) : Component.empty();
        Component next = page < pages ? msg.button("next", base + (page + 1)) : Component.empty();
        sender.sendMessage(msg.row(prev, Component.text(page + "/" + pages), next));
    }

    // ------------------------------------------------------------------ status

    private void status(CommandSender sender) {
        var st = plugin.state();
        String details = st.details().isEmpty() ? "-" : String.join("; ", st.details());
        String plugins = "-", updates = "-", pendingMatches = "-", last = "-", pending = "-", backups = "-";
        if (st.isOperational() && plugin.updates() != null) {
            UpdateService.Snapshot s = plugin.updates().snapshot();
            plugins = String.valueOf(s.statuses().size());
            updates = String.valueOf(s.updates().size());
            pendingMatches = String.valueOf(plugin.matches().pendingChoices().size());
            last = s.time() == 0 ? "noch nie" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(s.time()));
            pending = String.valueOf(plugin.transactions().all().size());
            backups = String.valueOf(plugin.backups().list().size());
        }
        msg.send(sender, "status", ph("state", st.state()), ph("details", details), ph("plugins", plugins), ph("updates", updates),
                ph("pending-matches", pendingMatches), ph("lastcheck", last), ph("pending", pending), ph("backups", backups),
                ph("operation", plugin.lock().holder() == null ? "keine" : plugin.lock().holder()),
                ph("postrestart", plugin.postRestartSummary()),
                ph("restart", plugin.restartDetail()), ph("compat", plugin.compat().status()));
    }

    // ----------------------------------------------------------------- restore

    private void restore(CommandSender sender, String[] args) {
        if (args.length < 2) { msg.send(sender, "usage", ph("usage", "/scamupdates restore [Plugin|all]")); return; }
        boolean all = args[1].equalsIgnoreCase("all");
        List<String> names = all ? List.of() : List.of(args[1]);
        if (all && plugin.backups().list().isEmpty()) { msg.send(sender, "restore.none-available"); return; }
        List<PluginInfo> metas = PluginScanner.collectMetadata();
        plugin.rollback().planAsync(metas, names).whenComplete((res, err) -> plugin.runMain(() -> {
            if (err != null) { reportFailure(sender, err); return; }
            for (RollbackService.Failure f : res.failures()) {
                switch (f.problem()) {
                    case NO_BACKUP -> msg.send(sender, "restore.no-backup", ph("plugin", f.plugin()));
                    case PENDING -> msg.send(sender, "restore.pending-blocked", ph("plugin", f.plugin()));
                    case NOT_INSTALLED -> msg.send(sender, "restore.failed", ph("plugin", f.plugin()), ph("error", "Plugin ist nicht installiert (JAR nicht gefunden)"));
                    case BACKUP_CORRUPT -> msg.send(sender, "restore.failed", ph("plugin", f.plugin()), ph("error", "Backup fehlt oder wurde veraendert"));
                    case IDENTICAL -> msg.send(sender, "restore.failed", ph("plugin", f.plugin()), ph("error", "Backup ist identisch mit der installierten Version"));
                }
            }
            if (res.plans().isEmpty()) return;
            if (res.plans().size() == 1) {
                RollbackService.Plan p = res.plans().get(0);
                confirmOrRun(sender, "restore", Perm.RESTORE, "restore-one",
                        new TagResolver[]{ph("plugin", p.backup().plugin()), ph("current", p.installed().version()), ph("backup", p.backup().version())},
                        () -> runRestore(sender, res.plans()));
            } else {
                for (RollbackService.Plan p : res.plans()) {
                    sender.sendMessage(msg.mini("<gray>- <white><plugin> <gray><current> <dark_gray>-> <green><backup>",
                            ph("plugin", p.backup().plugin()), ph("current", p.installed().version()), ph("backup", p.backup().version())));
                }
                confirmOrRun(sender, "restore", Perm.RESTORE, "restore-all", new TagResolver[]{ph("count", res.plans().size())},
                        () -> runRestore(sender, res.plans()));
            }
        }));
    }

    private void runRestore(CommandSender sender, List<RollbackService.Plan> plans) {
        plugin.log().info(sender.getName() + " startet Rollback fuer " + plans.size() + " Plugin(s).");
        plugin.rollback().executeAll(plans, it -> {
            if (it.ok()) msg.send(sender, "restore.staged", ph("plugin", it.plugin()), ph("from", it.from()), ph("to", it.to()));
            else msg.send(sender, "restore.failed", ph("plugin", it.plugin()), ph("error", it.error()));
        }, sender.getName()).whenComplete((items, err) -> plugin.runMain(() -> {
            if (err != null) { reportFailure(sender, err); return; }
            List<String> okNames = items.stream().filter(RollbackService.Item::ok).map(RollbackService.Item::plugin).toList();
            plugin.updates().markPending(okNames);
            if (!okNames.isEmpty()) {
                msg.send(sender, "restore.finished", ph("count", okNames.size()));
                if (plugin.restartAvailable()) sendRestartOffer(sender, okNames.size());
                else msg.send(sender, "download.no-restart-warning");
            }
            msg.playSound(sender, okNames.isEmpty() ? "error" : "success");
        }));
    }

    // ------------------------------------------------------------------ backup

    private void backup(CommandSender sender, String[] args) {
        if (args.length < 2) { msg.send(sender, "usage", ph("usage", "/scamupdates backup list|delete [Plugin|all]")); return; }
        if (args[1].equalsIgnoreCase("list")) {
            int page = 1;
            if (args.length >= 3) {
                try { page = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) { page = 1; }
            }
            List<BackupManager.Entry> all = plugin.backups().list();
            if (all.isEmpty()) { msg.send(sender, "backup.empty"); return; }
            int size = plugin.config().pageSize();
            int pages = Math.max(1, (all.size() + size - 1) / size);
            page = Math.min(page, pages);
            List<Component> lines = new ArrayList<>();
            lines.add(msg.render("backup.header", ph("page", page), ph("pages", pages)));
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
            for (BackupManager.Entry e : all.subList((page - 1) * size, Math.min(all.size(), page * size))) {
                lines.add(msg.render("backup.entry", ph("plugin", e.plugin()), ph("version", e.version()), ph("file", e.fileName()), ph("date", df.format(new Date(e.createdAt())))));
            }
            sender.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
            sendPager(sender, "/scamupdates backup list ", page, pages);
        } else if (args[1].equalsIgnoreCase("delete")) {
            if (args.length < 3) { msg.send(sender, "usage", ph("usage", "/scamupdates backup delete [Plugin|all]")); return; }
            if (args[2].equalsIgnoreCase("all")) {
                int count = plugin.backups().list().size();
                if (count == 0) { msg.send(sender, "backup.empty"); return; }
                confirmOrRun(sender, "backup-delete", Perm.BACKUP_DELETE, "backup-delete-all", new TagResolver[]{ph("count", count)},
                        () -> runDelete(sender, null));
            } else {
                Optional<BackupManager.Entry> e = plugin.backups().get(args[2]);
                if (e.isEmpty()) { msg.send(sender, "backup.none-for", ph("plugin", args[2])); return; }
                String name = e.get().plugin();
                confirmOrRun(sender, "backup-delete", Perm.BACKUP_DELETE, "backup-delete-one", new TagResolver[]{ph("plugin", name)},
                        () -> runDelete(sender, name));
            }
        } else {
            msg.send(sender, "usage", ph("usage", "/scamupdates backup list|delete [Plugin|all]"));
        }
    }

    private void runDelete(CommandSender sender, String pluginOrNull) {
        var handle = plugin.lock().tryAcquire("backup-delete");
        if (handle.isEmpty()) { msg.send(sender, "busy", ph("operation", String.valueOf(plugin.lock().holder()))); return; }
        plugin.io().submit(() -> {
            try (var h = handle.get()) {
                int n;
                if (pluginOrNull == null) n = plugin.backups().deleteAll();
                else n = plugin.backups().delete(pluginOrNull).isPresent() ? 1 : 0;
                int fn = n;
                if (plugin.config().historyEnabled()) {
                    plugin.history().append(HistoryEntry.of("backup-delete", "OK", sender.getName(),
                            fn + " Backup(s) geloescht" + (pluginOrNull == null ? " (alle)" : " (" + pluginOrNull + ")")));
                }
                plugin.runMain(() -> { msg.send(sender, "backup.deleted", ph("count", fn)); msg.playSound(sender, "success"); });
            } catch (Exception e) {
                plugin.log().error("Backup-Loeschung fehlgeschlagen", e);
                if (plugin.config().historyEnabled()) {
                    plugin.history().append(HistoryEntry.of("backup-delete", "FAILED", sender.getName(), plugin.log().redact(String.valueOf(e.getMessage()))));
                }
                plugin.runMain(() -> msg.send(sender, "backup.delete-failed", ph("error", plugin.log().redact(String.valueOf(e.getMessage())))));
            }
        });
    }

    // ----------------------------------------------------------------- restart

    private void restart(CommandSender sender) {
        if (plugin.config().crafty().enabled() && !plugin.config().crafty().restartEnabled()) { msg.send(sender, "restart.disabled"); return; }
        int pending = plugin.transactions().all().size();
        confirmOrRun(sender, "restart", Perm.RESTART, "restart", new TagResolver[]{ph("pending", pending)}, () -> {
            plugin.restart().restart(sender.getName()).whenComplete((out, err) -> plugin.runMain(() -> {
                if (err != null) { reportFailure(sender, err); return; }
                switch (out.result()) {
                    case REQUESTED -> { msg.send(sender, "restart.requested"); msg.playSound(sender, "success"); }
                    case REQUESTED_LOCAL -> { msg.send(sender, "restart.requested-local"); msg.playSound(sender, "success"); }
                    case PREFLIGHT_FAILED -> msg.send(sender, "restart.preflight-failed", ph("problems", String.join("\n", out.details())));
                    case DISABLED -> msg.send(sender, "restart.disabled");
                    case NO_PENDING -> msg.send(sender, "restart.no-pending");
                    case BUSY -> msg.send(sender, "busy", ph("operation", out.details().isEmpty() ? "?" : out.details().get(0)));
                    case FAILED -> { msg.send(sender, "restart.failed", ph("reason", String.join("; ", out.details()))); msg.playSound(sender, "error"); }
                }
            }));
        });
    }

    // ------------------------------------------------------------------ config

    private void config(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("reload")) { msg.send(sender, "usage", ph("usage", "/scamupdates config reload")); return; }
        if (plugin.lock().isBusy()) { msg.send(sender, "busy", ph("operation", String.valueOf(plugin.lock().holder()))); return; }
        if (plugin.state().state().isDisabled() && !plugin.config().allowReloadRecovery()) {
            msg.send(sender, "config.reload-failed", ph("reason", "startup.allow-reload-recovery ist deaktiviert - Server neu starten"));
            return;
        }
        plugin.log().info(sender.getName() + " laedt die Config neu.");
        msg.send(sender, "config.reloaded");
        plugin.startup().start(false);
    }

    // ----------------------------------------------------------------- sources

    private void sources(CommandSender sender) {
        List<Component> lines = new ArrayList<>();
        lines.add(msg.render("sources.header"));
        for (UpdateSource s : plugin.registry().all()) {
            lines.add(msg.render("sources.entry", ph("id", s.id()),
                    ph("state", plugin.registry().enabled(s.id()) ? "aktiv" : "deaktiviert"),
                    ph("search", s.supportsSearch() ? "ja" : "nur manuelle Zuordnung / Plugin-Metadaten")));
        }
        lines.add(msg.render("sources.manual", ph("count", plugin.config().manualMappingCount())));
        sender.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
    }

    // --------------------------------------------------------- exclude/include

    private Optional<String> canonicalName(String input) {
        Plugin p = Bukkit.getPluginManager().getPlugin(input);
        return p == null ? Optional.empty() : Optional.of(p.getName());
    }

    private void exclude(CommandSender sender, String[] args) {
        if (args.length < 2) { msg.send(sender, "usage", ph("usage", "/scamupdates exclude [Plugin]")); return; }
        Optional<String> name = canonicalName(args[1]);
        if (name.isEmpty()) { msg.send(sender, "unknown-plugin", ph("plugin", args[1])); return; }
        confirmOrRun(sender, "exclude", Perm.SOURCES, "exclude", new TagResolver[]{ph("plugin", name.get())}, () ->
                writeAsync(sender, () -> plugin.matches().store().exclude(name.get(), MatchStore.Reason.MANUAL_COMMAND), "exclude.done", name.get()));
    }

    private void include(CommandSender sender, String[] args) {
        if (args.length < 2) { msg.send(sender, "usage", ph("usage", "/scamupdates include [Plugin]")); return; }
        Optional<String> name = canonicalName(args[1]);
        if (name.isEmpty()) { msg.send(sender, "unknown-plugin", ph("plugin", args[1])); return; }
        Optional<MatchStore.Exclusion> ex = plugin.matches().store().exclusion(name.get());
        if (ex.isPresent() && ex.get().reason() == MatchStore.Reason.NONE_OF_THESE) {
            msg.send(sender, "include.refused", ph("plugin", name.get()));
            return;
        }
        confirmOrRun(sender, "include", Perm.SOURCES, "include", new TagResolver[]{ph("plugin", name.get())}, () ->
                writeAsync(sender, () -> plugin.matches().store().removeExclusion(name.get()), "include.done", name.get()));
    }

    private interface IoAction { void run() throws Exception; }

    private void writeAsync(CommandSender sender, IoAction action, String doneKey, String pluginName) {
        plugin.io().submit(() -> {
            try {
                action.run();
                plugin.log().info(sender.getName() + ": " + doneKey + " " + pluginName);
                plugin.runMain(() -> msg.send(sender, doneKey, ph("plugin", pluginName)));
            } catch (Exception e) {
                plugin.log().error("Speichern fehlgeschlagen", e);
                plugin.runMain(() -> msg.send(sender, "error-generic", ph("error", plugin.log().redact(String.valueOf(e.getMessage())))));
            }
        });
    }

    // ------------------------------------------------------------- match/pick

    private void match(CommandSender sender, String[] args) {
        MatchService ms = plugin.matches();
        if (args.length < 2) {
            List<MatchService.PendingChoice> all = ms.pendingChoices();
            if (all.isEmpty()) { msg.send(sender, "match.nothing-pending"); return; }
            List<Component> lines = new ArrayList<>();
            lines.add(msg.render("match.header"));
            for (MatchService.PendingChoice pc : all) {
                lines.add(msg.row(msg.render("match.entry", ph("plugin", pc.plugin()), ph("count", pc.candidates().size())),
                        msg.button("select", "/scamupdates match " + pc.plugin())));
            }
            sender.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
            return;
        }
        Optional<MatchService.PendingChoice> pc = ms.pendingFor(args[1]);
        if (pc.isEmpty()) { msg.send(sender, "match.stale"); return; }
        List<Component> lines = new ArrayList<>();
        lines.add(msg.render("match.options-header", ph("plugin", pc.get().plugin()), ph("count", pc.get().candidates().size())));
        int i = 1;
        for (var ref : pc.get().candidates()) {
            Component line = msg.render("match.option", ph("index", i), ph("name", ref.name()), ph("source", ref.sourceId()));
            lines.add(line.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/scamupdates pick " + pc.get().plugin() + " " + i))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(ref.url()))));
            i++;
        }
        lines.add(msg.button("pick-none", "/scamupdates pick " + pc.get().plugin() + " 0"));
        sender.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
    }

    private void pick(CommandSender sender, String[] args) {
        if (args.length < 3) { msg.send(sender, "usage", ph("usage", "/scamupdates pick [Plugin] [Nummer|0=Keins davon]")); return; }
        final int idx = parseIndex(args[2]);
        if (idx < 0) { msg.send(sender, "match.stale"); return; }
        Optional<MatchService.PendingChoice> pc = plugin.matches().pendingFor(args[1]);
        MatchService.PickResult r = plugin.matches().pick(args[1], idx);
        switch (r) {
            case ASSIGNED -> {
                String name = pc.map(c -> c.candidates().get(idx - 1).name()).orElse("?");
                msg.send(sender, "match.picked", ph("plugin", pc.map(MatchService.PendingChoice::plugin).orElse(args[1])), ph("name", name));
                plugin.log().info(sender.getName() + " hat " + args[1] + " zugeordnet (Auswahl " + idx + ").");
            }
            case EXCLUDED -> {
                msg.send(sender, "match.excluded", ph("plugin", pc.map(MatchService.PendingChoice::plugin).orElse(args[1])));
                plugin.log().info(sender.getName() + " hat 'Keins davon' fuer " + args[1] + " gewaehlt.");
            }
            case NOT_PENDING, BAD_INDEX -> msg.send(sender, "match.stale");
            case STORAGE_ERROR -> msg.send(sender, "error-generic", ph("error", "matches.yml konnte nicht gespeichert werden"));
        }
    }

    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ history

    private void history(CommandSender sender) {
        if (sender instanceof Player p && plugin.gui() != null && plugin.gui().enabled()) {
            plugin.gui().openHistory(p);
            return;
        }
        List<HistoryEntry> all = plugin.history().list(HistoryStore.Sort.NEWEST);
        if (all.isEmpty()) { msg.send(sender, "history.empty"); return; }
        int n = Math.min(10, all.size());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
        List<Component> lines = new ArrayList<>();
        lines.add(msg.render("history.header", ph("count", n)));
        for (int i = 0; i < n; i++) {
            HistoryEntry e = all.get(i);
            lines.add(msg.render("history.entry", ph("time", fmt.format(Instant.ofEpochMilli(e.time()))), ph("action", e.action()),
                    ph("result", e.result()), ph("actor", e.actor()), ph("message", e.message())));
        }
        sender.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
    }

    // ---------------------------------------------------------------- Tab-Tipp

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : new TreeSet<>(CommandPolicy.SUBCOMMANDS)) {
                Perm p = CommandPolicy.required(new String[]{s});
                if (p == null || p.allows(n -> sender.hasPermission(n))) out.add(s);
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "restore" -> { out.add("all"); out.addAll(pluginNames()); }
                case "exclude", "include", "match", "pick" -> out.addAll(pluginNames());
                case "backup" -> out.addAll(List.of("list", "delete"));
                case "config" -> out.add("reload");
                case "list" -> out.addAll(List.of("updates", "all"));
                default -> { }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("backup") && args[1].equalsIgnoreCase("delete")) {
            out.add("all");
            out.addAll(plugin.state().isOperational() ? plugin.backups().list().stream().map(BackupManager.Entry::plugin).toList() : List.of());
        }
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).collect(Collectors.toList());
    }

    private List<String> pluginNames() {
        return Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(Plugin::getName).toList();
    }
}
