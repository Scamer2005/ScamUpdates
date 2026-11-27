package de.scamupdates.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.scamupdates.logging.ScamLog;
import de.scamupdates.utils.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntSupplier;

/** Dauerhaftes Update-Protokoll (history.jsonl, eine JSON-Zeile je Eintrag). Geheimnisse werden vor dem Schreiben geschwaerzt. */
public final class HistoryStore {

    public enum Sort { NEWEST, OLDEST, PROBLEMS_FIRST }

    private final Path file;
    private final ScamLog log;
    private final IntSupplier retentionDays;
    private final IntSupplier maxEntries;

    public HistoryStore(Path file, ScamLog log, IntSupplier retentionDays, IntSupplier maxEntries) {
        this.file = file;
        this.log = log;
        this.retentionDays = retentionDays;
        this.maxEntries = maxEntries;
    }

    public HistoryStore(Path file, ScamLog log, int retentionDays, int maxEntries) {
        this(file, log, () -> retentionDays, () -> maxEntries);
    }

    public synchronized void append(HistoryEntry e) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, toJson(e) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.error("Protokolleintrag konnte nicht gespeichert werden", ex);
        }
    }

    public synchronized List<HistoryEntry> list(Sort sort) {
        List<HistoryEntry> all = readAll();
        Comparator<HistoryEntry> byTimeDesc = Comparator.comparingLong(HistoryEntry::time).reversed();
        switch (sort) {
            case OLDEST -> all.sort(Comparator.comparingLong(HistoryEntry::time));
            case PROBLEMS_FIRST -> all.sort(Comparator.comparingInt((HistoryEntry x) -> severity(x.result())).reversed().thenComparing(byTimeDesc));
            default -> all.sort(byTimeDesc);
        }
        return all;
    }

    /** Entfernt Eintraege ausserhalb der Aufbewahrungsdauer bzw. ueber dem Maximum. @return Anzahl entfernter Eintraege */
    public synchronized int prune(long now) {
        List<HistoryEntry> all = readAll();
        all.sort(Comparator.comparingLong(HistoryEntry::time));
        int before = all.size();
        int days = retentionDays.getAsInt();
        if (days > 0) {
            long cutoff = now - days * 86_400_000L;
            all.removeIf(x -> x.time() < cutoff);
        }
        int max = Math.max(10, maxEntries.getAsInt());
        while (all.size() > max) all.remove(0);
        int removed = before - all.size();
        if (removed > 0) {
            StringBuilder sb = new StringBuilder();
            for (HistoryEntry e : all) sb.append(toJson(e)).append(System.lineSeparator());
            try {
                AtomicFiles.writeString(file, sb.toString());
                log.info("Update-Protokoll bereinigt: " + removed + " alte(r) Eintrag/Eintraege entfernt.");
            } catch (IOException ex) {
                log.error("Update-Protokoll konnte nicht bereinigt werden", ex);
                return 0;
            }
        }
        return removed;
    }

    private static int severity(String result) {
        return switch (result == null ? "" : result) {
            case "FAILED" -> 3;
            case "WARN" -> 2;
            case "OK" -> 1;
            default -> 0;
        };
    }

    private List<HistoryEntry> readAll() {
        List<HistoryEntry> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        int bad = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    out.add(fromJson(JsonParser.parseString(line).getAsJsonObject()));
                } catch (RuntimeException ex) {
                    bad++;
                }
            }
        } catch (IOException ex) {
            log.error("Update-Protokoll nicht lesbar", ex);
        }
        if (bad > 0) log.warn(bad + " beschaedigte Zeile(n) im Update-Protokoll wurden ignoriert.");
        return out;
    }

    private String toJson(HistoryEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("time", e.time());
        o.addProperty("action", log.redact(e.action()));
        o.addProperty("result", e.result());
        o.addProperty("actor", log.redact(e.actor()));
        o.addProperty("mc", log.redact(e.mcTarget()));
        o.addProperty("message", log.redact(e.message()));
        JsonArray arr = new JsonArray();
        for (HistoryEntry.Detail d : e.details()) {
            JsonObject j = new JsonObject();
            j.addProperty("plugin", log.redact(d.plugin()));
            j.addProperty("from", log.redact(d.from()));
            j.addProperty("to", log.redact(d.to()));
            j.addProperty("status", log.redact(d.status()));
            arr.add(j);
        }
        o.add("details", arr);
        return o.toString();
    }

    private static HistoryEntry fromJson(JsonObject o) {
        List<HistoryEntry.Detail> details = new ArrayList<>();
        JsonElement de = o.get("details");
        if (de != null && de.isJsonArray()) {
            for (JsonElement el : de.getAsJsonArray()) {
                JsonObject j = el.getAsJsonObject();
                details.add(new HistoryEntry.Detail(str(j, "plugin"), str(j, "from"), str(j, "to"), str(j, "status")));
            }
        }
        return new HistoryEntry(o.get("time").getAsLong(), str(o, "action"), str(o, "result"), str(o, "actor"),
                str(o, "mc"), str(o, "message"), details);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
