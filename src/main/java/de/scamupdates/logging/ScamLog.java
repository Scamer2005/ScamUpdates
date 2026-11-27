package de.scamupdates.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Eigenes Logging: Konsole + Datei, mit Schwaerzung registrierter Geheimnisse (API-Tokens). */
public final class ScamLog {
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final Logger logger;
    private final Path dir;
    private final Set<String> secrets = ConcurrentHashMap.newKeySet();
    private final Object fileLock = new Object();
    private volatile boolean debug;

    public ScamLog(Logger logger, Path dir) {
        this.logger = logger;
        this.dir = dir;
    }

    public void setDebug(boolean debug) { this.debug = debug; }
    public boolean isDebug() { return debug; }

    public void registerSecret(String secret) {
        if (secret != null && secret.length() >= 6) secrets.add(secret);
    }

    public void clearSecrets() { secrets.clear(); }

    public String redact(String text) {
        if (text == null) return "";
        String out = text;
        for (String s : secrets) out = out.replace(s, "***");
        return out;
    }

    public void info(String msg) {
        logger.info(redact(msg));
        write("INFO", msg);
    }

    public void warn(String msg) {
        logger.warning(redact(msg));
        write("WARN", msg);
    }

    public void error(String msg, Throwable t) {
        String full = msg + (t == null ? "" : " (" + t.getClass().getSimpleName() + ": " + redact(String.valueOf(t.getMessage())) + ")");
        logger.severe(redact(full));
        write("ERROR", full);
        if (t != null && debug) logger.log(Level.SEVERE, "Stacktrace", t);
    }

    public void debug(String msg) {
        if (!debug) return;
        logger.info("[DEBUG] " + redact(msg));
        write("DEBUG", msg);
    }

    private void write(String level, String msg) {
        if (dir == null) return;
        synchronized (fileLock) {
            try {
                Files.createDirectories(dir);
                Path f = dir.resolve("scamupdates.log");
                if (Files.exists(f) && Files.size(f) > MAX_BYTES) {
                    Files.move(f, dir.resolve("scamupdates.log.1"), StandardCopyOption.REPLACE_EXISTING);
                }
                String line = Instant.now() + " [" + level + "] " + redact(msg).replace('\n', ' ') + System.lineSeparator();
                Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Logdatei nicht beschreibbar: " + e.getMessage());
            }
        }
    }
}
