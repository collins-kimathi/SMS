package com.uniongroup.sms;

import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// Password hashing + verification (PBKDF2) with legacy support
final class PasswordUtils {

    private static final String PASSWORD_PREFIX = "pbkdf2$";
    private static final int PASSWORD_ITERATIONS = 120000;
    private static final int PASSWORD_KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtils() {}

    // Detect hashed format (used for backward compatibility)
    static boolean isHashedPassword(String password) {
        return password != null && password.startsWith(PASSWORD_PREFIX);
    }

    // Verify password against stored value (supports plain + hashed)
    static boolean verifyPassword(String rawPassword, String storedPassword) {
        if (!isHashedPassword(storedPassword)) {
            // legacy plain-text comparison
            return storedPassword.equals(rawPassword);
        }

        String[] parts = storedPassword.split("\\$");
        if (parts.length != 4) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = decodeBase64(parts[2]);
            byte[] expected = decodeBase64(parts[3]);

            byte[] actual = derivePassword(
                rawPassword.toCharArray(),
                salt,
                iterations,
                expected.length * 8
            );

            return constantTimeEquals(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Upgrade plain-text password to hashed format on login
    static void upgradePlainTextPasswordIfNeeded(Connection connection, int userId, String storedPassword) throws SQLException {
        if (isHashedPassword(storedPassword)) {
            return;
        }

        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE users SET password = ? WHERE user_id = ?")) {

            ps.setString(1, hashPassword(storedPassword));
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    // Generate PBKDF2 hash with random salt
    static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);

        byte[] hash = derivePassword(
            password.toCharArray(),
            salt,
            PASSWORD_ITERATIONS,
            PASSWORD_KEY_LENGTH
        );

        return PASSWORD_PREFIX
            + PASSWORD_ITERATIONS + "$"
            + encodeBase64(salt) + "$"
            + encodeBase64(hash);
    }

    // Core PBKDF2 derivation
    private static byte[] derivePassword(char[] password, byte[] salt, int iterations, int keyLength) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (InvalidKeySpecException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to hash password", e);
        }
    }

    // Prevent timing attacks during comparison
    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != right.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < left.length; i++) {
            result |= left[i] ^ right[i];
        }
        return result == 0;
    }

    private static String encodeBase64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decodeBase64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}