package de.scamupdates.update;

import de.scamupdates.backup.BackupManager;
import de.scamupdates.utils.AtomicFiles;
import de.scamupdates.utils.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Legt eine validierte JAR in plugins/update/ ab. Paper/Bukkit uebernimmt Dateien aus diesem Ordner beim naechsten
 * Start NUR bei gleichem Dateinamen wie die installierte JAR - der Name wird deshalb von der installierten Datei uebernommen.
 * Reihenfolge schuetzt das bisherige Backup: erst Backup-Kopie vorbereiten, dann stagen, dann Backup ersetzen.
 */
public final class Stager {

    public record Result(Path stagedFile, String sha256, BackupManager.Entry backup) {}

    private final Path updateDir;
    private final BackupManager backups;

    public Stager(Path updateDir, BackupManager backups) {
        this.updateDir = updateDir;
        this.backups = backups;
    }

    public Path updateDir() { return updateDir; }

    public Result stage(String plugin, String installedVersion, Path installedJar, Path validatedTemp) throws IOException {
        String fileName = installedJar.getFileName().toString();
        Path target = AtomicFiles.requireInside(updateDir, fileName);
        if (Files.isDirectory(target)) throw new IOException("Zieldatei ist ein Verzeichnis: " + fileName);
        Files.createDirectories(updateDir);
        BackupManager.Prepared pb = backups.prepare(plugin, installedVersion, installedJar);
        String sha = Hashing.sha256(validatedTemp);
        try {
            AtomicFiles.moveReplace(validatedTemp, target);
        } catch (IOException | RuntimeException e) {
            backups.discard(pb);
            throw e;
        }
        try {
            backups.commit(pb);
        } catch (IOException | RuntimeException e) {
            AtomicFiles.deleteQuietly(target);
            backups.discard(pb);
            throw e;
        }
        return new Result(target, sha, pb.entry());
    }
}
