package de.scamupdates.gui;

import de.scamupdates.match.MatchService;
import de.scamupdates.model.ProjectRef;
import de.scamupdates.queue.QueueService;
import de.scamupdates.security.Perm;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static de.scamupdates.messaging.Messages.ph;

/**
 * Auswahlmenue fuer mehrdeutige Update-Kandidaten eines Plugins.
 * Waehlt niemals automatisch; bietet explizit alle Kandidaten sowie "Keins davon".
 */
final class MatchChoiceMenu extends PagedMenu<MatchChoiceMenu.IndexedCandidate> {

    record IndexedCandidate(int index, ProjectRef ref) {}

    private final String pluginName;
    private final Runnable onBack;
    private int lastCandidateCount = -1;

    MatchChoiceMenu(GuiService gui, Player viewer, String pluginName, Runnable onBack) {
        super(gui, viewer);
        this.pluginName = pluginName;
        this.onBack = onBack;
    }

    @Override protected int rows() { return 6; }

    @Override protected Component title() {
        return gui.config().title("match", ph("page", pageNumber()), ph("pages", pageCount()), ph("plugin", pluginName));
    }

    @Override
    protected List<IndexedCandidate> entries() {
        Optional<MatchService.PendingChoice> pc = gui.plugin().matches().pendingFor(pluginName);
        if (pc.isEmpty()) return List.of();
        List<IndexedCandidate> out = new ArrayList<>();
        int i = 1;
        for (ProjectRef ref : pc.get().candidates()) {
            out.add(new IndexedCandidate(i++, ref));
        }
        return out;
    }

    @Override
    protected Button button(IndexedCandidate entry) {
        ProjectRef r = entry.ref();
        return Button.of(gui.item("match-candidate", Material.PAPER,
                ph("index", entry.index()),
                ph("name", r.name()),
                ph("source", r.sourceId()),
                ph("project", r.projectId()),
                ph("url", r.url().isBlank() ? "-" : r.url())).build(), Perm.SOURCES, c -> pick(entry.index(), r.name()));
    }

    @Override
    protected Button emptyButton() {
        return Button.display(gui.item("match-empty", Material.STRUCTURE_VOID).build());
    }

    @Override
    protected void extras(List<IndexedCandidate> all) {
        lastCandidateCount = all.size();
        if (!all.isEmpty()) {
            set(Slots.CONFIRM, Button.of(gui.item("match-none", Material.BARRIER).build(), Perm.SOURCES, c -> pickNone()));
        }
    }

    private void pick(int index, String candidateName) {
        MatchService.PickResult res = gui.plugin().matches().pick(pluginName, index);
        switch (res) {
            case ASSIGNED -> {
                if (gui.plugin().updates() != null) {
                    gui.plugin().updates().clearPendingMatch(pluginName);
                }
                viewer.sendMessage(gui.plugin().messages().render("match.picked", ph("plugin", pluginName), ph("name", candidateName)));
                gui.plugin().log().info(viewer.getName() + " hat " + pluginName + " im Menue zugeordnet (" + candidateName + ").");
                if (gui.plugin().updates() != null && gui.plugin().state().isOnline()) {
                    try {
                        gui.plugin().updates().checkSingle(pluginName, viewer.getName())
                                .whenComplete((st, err) -> {
                                    if (err == null && gui.plugin().notifier() != null) {
                                        gui.plugin().runMain(() -> gui.plugin().notifier().broadcastQueueNotifications());
                                    }
                                });
                    } catch (RuntimeException ignored) {
                        // Einzelpruefung wird beim naechsten Check nachgeholt
                    }
                }
                back();
            }
            case STORAGE_ERROR -> viewer.sendMessage(gui.plugin().messages().render("error-generic", ph("error", "matches.yml konnte nicht gespeichert werden")));
            default -> {
                viewer.sendMessage(gui.plugin().messages().render("match.stale"));
                back();
            }
        }
    }

    private void pickNone() {
        QueueService qs = gui.plugin().queueService();
        MatchService.PickResult res = qs != null
                ? qs.chooseNone(pluginName, gui.plugin().matches(), viewer.getName())
                : gui.plugin().matches().pick(pluginName, 0);
        if (res == MatchService.PickResult.EXCLUDED) {
            if (gui.plugin().updates() != null) {
                gui.plugin().updates().markExcluded(pluginName);
            }
            viewer.sendMessage(gui.plugin().messages().render("match.excluded", ph("plugin", pluginName)));
        } else {
            viewer.sendMessage(gui.plugin().messages().render("match.stale"));
        }
        back();
    }

    @Override
    protected boolean needsRender() {
        int cur = gui.plugin().matches() == null ? 0
                : gui.plugin().matches().pendingFor(pluginName).map(c -> c.candidates().size()).orElse(0);
        return cur != lastCandidateCount;
    }

    @Override protected void back() { onBack.run(); }
}
