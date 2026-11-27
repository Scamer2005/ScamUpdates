package de.scamupdates.update;

import de.scamupdates.model.UpdateCandidate;
import de.scamupdates.utils.AtomicFiles;
import de.scamupdates.utils.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Download in eine Temp-Datei, Pruefsummen- und JAR-Validierung. Die installierte JAR wird nie angefasst. */
public final class DownloadPipeline {

    public interface Fetcher {
        void fetch(String url, Path target) throws IOException, InterruptedException;
    }

    public static final class DownloadException extends Exception {
        public DownloadException(String message) { super(message); }
    }

    public record Prepared(UpdateCandidate candidate, Path tmp, String sha256) {}

    private final Path tmpDir;
    private final Fetcher fetcher;
    private final String mcVersion;
    private final boolean requireChecksum;

    public DownloadPipeline(Path tmpDir, Fetcher fetcher, String mcVersion, boolean requireChecksum) {
        this.tmpDir = tmpDir;
        this.fetcher = fetcher;
        this.mcVersion = mcVersion;
        this.requireChecksum = requireChecksum;
    }

    public Prepared prepare(UpdateCandidate c) throws DownloadException, InterruptedException {
        String url = c.release().downloadUrl();
        if (url == null || url.isBlank()) {
            throw new DownloadException("Die Quelle bietet keinen Direktdownload an (manuell herunterladen: " + c.project().url() + ")");
        }
        Path tmp = tmpDir.resolve(UUID.randomUUID() + ".part");
        boolean success = false;
        try {
            Files.createDirectories(tmpDir);
            fetcher.fetch(url, tmp);
            boolean verified = verifyChecksums(tmp, c);
            if (requireChecksum && !verified) throw new DownloadException("Keine Pruefsumme verfuegbar (downloads.require-checksum ist aktiv)");
            try {
                JarValidator.validate(tmp, c.plugin().name(), mcVersion);
            } catch (JarValidator.ValidationException e) {
                throw new DownloadException(e.getMessage());
            }
            Prepared p = new Prepared(c, tmp, Hashing.sha256(tmp));
            success = true;
            return p;
        } catch (IOException e) {
            throw new DownloadException("Download fehlgeschlagen: " + e.getMessage());
        } finally {
            if (!success) AtomicFiles.deleteQuietly(tmp);
        }
    }

    /** @return true, wenn mindestens eine Pruefsumme verifiziert wurde. */
    private boolean verifyChecksums(Path file, UpdateCandidate c) throws IOException, DownloadException {
        boolean verified = false;
        for (Map.Entry<String, String> h : c.release().hashes().entrySet()) {
            String alg = Hashing.normalizeAlgorithm(h.getKey());
            if (alg == null || h.getValue() == null || h.getValue().isBlank()) continue;
            if (!Hashing.digest(file, alg).equalsIgnoreCase(h.getValue().trim())) {
                throw new DownloadException("Pruefsumme (" + alg + ") stimmt nicht ueberein - Datei verworfen");
            }
            verified = true;
        }
        String manual = c.project().extra().get("sha256");
        if (manual != null && !manual.isBlank()) {
            if (!Hashing.sha256(file).equalsIgnoreCase(manual.trim())) {
                throw new DownloadException("Konfigurierte SHA-256-Pruefsumme stimmt nicht ueberein - Datei verworfen");
            }
            verified = true;
        }
        return verified;
    }
}
