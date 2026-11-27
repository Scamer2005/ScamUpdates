package de.scamupdates.update;

import de.scamupdates.logging.ScamLog;
import de.scamupdates.scan.PluginJarIndex;
import de.scamupdates.utils.AtomicFiles;
import de.scamupdates.utils.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Prueft nach einem Neustart, ob vorgemerkte Aenderungen uebernommen wurden: die JAR im plugins-Ordner muss den
 * gespeicherten SHA-256 der vorgemerkten Datei haben. Das ist unabhaengig von Versionsstring-Formaten.
 */
public final class PostRestartVerifier {
    private PostRestartVerifier() {}

    public enum Status { APPLIED, NOT_APPLIED, MISMATCH }

    public record Report(String plugin, String expectedVersion, Status status, String detail) {}

    public static List<Report> verify(TransactionStore store, Path pluginsDir, Path updateDir, ScamLog log) {
        List<Report> reports = new ArrayList<>();
        List<TransactionStore.Pending> pending = store.all();
        if (pending.isEmpty()) return reports;
        Map<String, Path> index;
        try {
            index = PluginJarIndex.build(pluginsDir);
        } catch (IOException e) {
            log.error("Neustart-Pruefung: plugins-Ordner nicht lesbar", e);
            return reports;
        }
        for (TransactionStore.Pending p : pending) {
            Path installed = index.get(p.plugin().toLowerCase(Locale.ROOT));
            Report r;
            try {
                Path staged = AtomicFiles.requireInside(updateDir, p.stagedFile());
                if (installed != null && Hashing.sha256(installed).equalsIgnoreCase(p.stagedSha256())) {
                    r = new Report(p.plugin(), p.toVersion(), Status.APPLIED, "uebernommen");
                    store.remove(p.plugin());
                } else if (Files.isRegularFile(staged)) {
                    r = new Report(p.plugin(), p.toVersion(), Status.NOT_APPLIED,
                            "Datei liegt noch in plugins/update (Paper hat sie nicht uebernommen; Dateiname/Rechte pruefen)");
                } else {
                    r = new Report(p.plugin(), p.toVersion(), Status.MISMATCH,
                            "Datei nicht mehr vorgemerkt, installierte JAR weicht ab (extern geaendert?)");
                    store.remove(p.plugin());
                }
            } catch (IOException e) {
                r = new Report(p.plugin(), p.toVersion(), Status.MISMATCH, "Pruefung fehlgeschlagen: " + e.getMessage());
            }
            reports.add(r);
            String line = "Neustart-Pruefung " + r.plugin() + " -> " + r.expectedVersion() + ": " + r.status() + " (" + r.detail() + ")";
            if (r.status() == Status.APPLIED) log.info(line); else log.warn(line);
        }
        return reports;
    }
}
