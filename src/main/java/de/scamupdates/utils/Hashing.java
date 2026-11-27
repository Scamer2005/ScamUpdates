package de.scamupdates.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class Hashing {
    private Hashing() {}

    public static String sha256(Path file) throws IOException {
        return digest(file, "SHA-256");
    }

    public static String digest(Path file, String algorithm) throws IOException {
        MessageDigest md = newDigest(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Digest not available: " + algorithm, e);
        }
    }

    /** Liefert den JDK-Algorithmusnamen (SHA-1, SHA-256, SHA-512) oder null, falls nicht unterstuetzt. */
    public static String normalizeAlgorithm(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (n) {
            case "sha1" -> "SHA-1";
            case "sha256" -> "SHA-256";
            case "sha512" -> "SHA-512";
            default -> null;
        };
    }
}
