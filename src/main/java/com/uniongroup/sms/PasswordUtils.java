package com.uniongroup.sms;

import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// Central password helper for hashing, verification, and legacy-password upgrades.
final class PasswordUtils {

    private static final String PASSWORD_PREFIX = "pbkdf2$";
    private static final int PASSWORD_ITERATIONS = 120000;
    private static final int PASSWORD_KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtils() {
    }

    static boolean isHashedPassword(String password) {
        return password != null && password.startsWith(PASSWORD_PREFIX);
    }

    static boolean verifyPassword(String rawPassword, String storedPassword) {
        if (!isHashedPassword(storedPassword)) {
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
            byte[] actual = derivePassword(rawPassword.toCharArray(), salt, iterations, expected.length * 8);
            return constantTimeEquals(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static void upgradePlainTextPasswordIfNeeded(Connection connection, int userId, String storedPassword) throws SQLException {
        if (isHashedPassword(storedPassword)) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("UPDATE users SET password = ? WHERE user_id = ?")) {
            statement.setString(1, hashPassword(storedPassword));
            statement.setInt(2, userId);
            statement.executeUpdate();
        }
    }

    static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derivePassword(password.toCharArray(), salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH);
        return PASSWORD_PREFIX + PASSWORD_ITERATIONS + "$" + encodeBase64(salt) + "$" + encodeBase64(hash);
    }

    private static byte[] derivePassword(char[] password, byte[] salt, int iterations, int keyLength) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (InvalidKeySpecException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Failed to hash password", exception);
        }
    }

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != right.length) {
            return false;
        }

        int result = 0;
        for (int index = 0; index < left.length; index++) {
            result |= left[index] ^ right[index];
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
