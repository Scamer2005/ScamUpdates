package de.scamupdates.gui;

/** Einheitliche Positionen im 6-reihigen Menue. Unten rechts bleibt ein Feld Abstand zum Rand (Slot 52). */
public final class Slots {
    private Slots() {}

    public static final int BACK = 46;
    public static final int EXTRA_LEFT = 47;
    public static final int PREV = 48;
    public static final int INFO = 49;
    public static final int NEXT = 50;
    public static final int EXTRA_RIGHT = 51;
    public static final int CONFIRM = 52; // Weiter / Barrier zum Abbrechen
    public static final int CLOSE = 49;

    /** Inhaltsbereich: 4 Reihen x 7 Spalten innerhalb des Rahmens. */
    public static final int[] CONTENT = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    public static int at(int row, int col) { return row * 9 + col; }
}
