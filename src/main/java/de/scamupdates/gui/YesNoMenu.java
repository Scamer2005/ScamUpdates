package de.scamupdates.gui;

import de.scamupdates.messaging.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Einfache Ja/Nein-Abfrage (z. B. zum Abbrechen einer laufenden Pruefung). */
final class YesNoMenu extends Menu {
    private final Component question;
    private final Runnable yes;
    private final Runnable no;

    YesNoMenu(GuiService gui, Player viewer, Component question, Runnable yes, Runnable no) {
        super(gui, viewer);
        this.question = question;
        this.yes = yes;
        this.no = no;
    }

    @Override protected int rows() { return 3; }

    @Override protected Component title() { return gui.config().title("yesno"); }

    @Override
    protected void build() {
        frame();
        set(13, gui.item("yesno-question", Material.PAPER, Messages.phc("question", question)).build());
        set(11, Button.of(gui.item("yes", Material.LIME_CONCRETE).build(), c -> yes.run()));
        set(15, Button.of(gui.item("no", Material.RED_CONCRETE).build(), c -> no.run()));
    }
}
