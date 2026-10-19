package com.fongmi.android.tv.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * SHA-256 helpers shared by the catalog, the module manifest and the loader.
 *
 * <p>Kept free of Android APIs so the contract layer stays unit-testable on a plain JVM.
 */
public final class RuntimeIntegrity {

    public static final String PREFIX = "sha256:";
    private static final String ALGORITHM = "SHA-256";
    private static final int HEX_LENGTH = 64;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private RuntimeIntegrity() {
    }

    public static String sha256(byte[] data) {
        return toHex(digest().digest(data == null ? new byte[0] : data));
    }

    public static String sha256(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Not a readable file: " + file);
        try (InputStream in = new FileInputStream(file)) {
            return sha256(in);
        }
    }

    /**
     * Digests a stream, for payloads that are not files on disk.
     *
     * <p>A module's assets live inside its APK, so they are read through an {@code AssetManager} or a
     * zip entry rather than a {@code File}. The stream is consumed but not closed, because the caller
     * owns it.
     */
    public static String sha256(InputStream in) throws IOException {
        if (in == null) throw new IOException("Not a readable stream");
        MessageDigest digest = digest();
        byte[] buffer = new byte[65536];
        int read;
        while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        return toHex(digest.digest());
    }

    /** Case-insensitive comparison that also tolerates a leading {@code sha256:}. */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) return false;
        String left = normalize(expected);
        String right = normalize(actual);
        if (!isSha256(left) || !isSha256(right)) return false;
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    /** Trims, lowercases and strips a leading {@code sha256:} prefix. */
    public static String normalize(String digest) {
        String text = digest == null ? "" : digest.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith(PREFIX)) text = text.substring(PREFIX.length()).trim();
        return text;
    }

    public static boolean isSha256(String value) {
        String text = normalize(value);
        if (text.length() != HEX_LENGTH) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable on this runtime", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }
}
