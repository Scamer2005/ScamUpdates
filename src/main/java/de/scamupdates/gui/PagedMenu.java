package de.scamupdates.gui;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

import static de.scamupdates.messaging.Messages.ph;

/** Seitenweises Menue (28 Eintraege pro Seite). Seitenwechsel oeffnet das Menue neu, damit der Titel stimmt. */
public abstract class PagedMenu<T> extends Menu {
    protected int page;
    private int pages = 1;

    protected PagedMenu(GuiService gui, Player viewer) { super(gui, viewer); }

    protected abstract List<T> entries();

    protected abstract Button button(T entry);

    /** Platzhalter-Button, wenn keine Eintraege vorhanden sind. */
    protected abstract Button emptyButton();

    /** Zusaetzliche Bedienelemente (Filter, Sortierung) in den Slots EXTRA_LEFT/EXTRA_RIGHT. */
    protected void extras() {}

    /** Variante mit bereits geladenen Eintraegen, damit teure Auswertungen nicht mehrfach laufen. */
    protected void extras(List<T> all) { extras(); }

    protected void back() {}

    protected boolean hasBack() { return true; }

    protected final int pageNumber() { return page + 1; }

    protected final int pageCount() {
        int n = entries().size();
        return Math.max(1, (n + Slots.CONTENT.length - 1) / Slots.CONTENT.length);
    }

    protected final TagResolver[] pageResolvers() {
        return new TagResolver[]{ph("page", pageNumber()), ph("pages", pageCount())};
    }

    @Override
    protected final void build() {
        frame();
        List<T> all = entries();
        pages = Math.max(1, (all.size() + Slots.CONTENT.length - 1) / Slots.CONTENT.length);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        if (all.isEmpty()) {
            set(22, emptyButton());
        } else {
            int from = page * Slots.CONTENT.length;
            for (int i = 0; i < Slots.CONTENT.length && from + i < all.size(); i++) {
                set(Slots.CONTENT[i], button(all.get(from + i)));
            }
        }
        TagResolver[] r = {ph("page", page + 1), ph("pages", pages), ph("count", all.size())};
        set(Slots.INFO, gui.config().item("nav-info", Material.OAK_SIGN, r).build());
        if (page > 0) set(Slots.PREV, Button.of(gui.config().item("nav-prev", Material.SPECTRAL_ARROW, r).build(), c -> goTo(page - 1)));
        if (page < pages - 1) set(Slots.NEXT, Button.of(gui.config().item("nav-next", Material.SPECTRAL_ARROW, r).build(), c -> goTo(page + 1)));
        if (hasBack()) backButton(this::back);
        extras(all);
    }

    protected final void goTo(int p) {
        page = Math.max(0, p);
        open();
    }
}
