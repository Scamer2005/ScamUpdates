package de.scamupdates.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AtomicFiles {
    private AtomicFiles() {}

    public static void moveReplace(Path src, Path dst) throws IOException {
        Path parent = dst.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void writeString(Path target, String content) throws IOException {
        Path abs = target.toAbsolutePath();
        Files.createDirectories(abs.getParent());
        Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        moveReplace(tmp, abs);
    }

    /** Loest einen Dateinamen sicher innerhalb von base auf (Schutz vor Path Traversal). */
    public static Path requireInside(Path base, String fileName) throws IOException {
        if (fileName == null || fileName.isBlank()
                || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..") || fileName.indexOf('\0') >= 0) {
            throw new IOException("Unsicherer Dateiname: " + fileName);
        }
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path resolved = normalizedBase.resolve(fileName).normalize();
        if (!resolved.startsWith(normalizedBase) || resolved.equals(normalizedBase)) {
            throw new IOException("Dateiname verlaesst das Zielverzeichnis: " + fileName);
        }
        return resolved;
    }

    public static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // Best effort: temporaere Datei, die naechste Startbereinigung raeumt auf.
        }
    }
}
