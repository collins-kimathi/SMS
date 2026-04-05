package com.uniongroup.sms;

import java.io.IOException;
import java.sql.*;

// Initializes database schema and performs startup migrations
final class DatabaseBootstrap {

    private DatabaseBootstrap() {}

    // Entry point for DB setup (runs once at server startup)
    static void initialize(String dbUrl, String dbUser, String dbPassword) throws IOException {
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {

            // Ensure required columns exist (safe schema evolution)
            ensureColumnExists(connection, "incidents", "incident_type",
                "ALTER TABLE incidents ADD COLUMN incident_type VARCHAR(50) NOT NULL DEFAULT 'Security'");
            ensureColumnExists(connection, "incidents", "location",
                "ALTER TABLE incidents ADD COLUMN location VARCHAR(150) NOT NULL DEFAULT 'Main Facility'");
            ensureColumnExists(connection, "incidents", "status",
                "ALTER TABLE incidents ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'Open'");
            ensureColumnExists(connection, "incidents", "action_taken",
                "ALTER TABLE incidents ADD COLUMN action_taken TEXT NULL");

            ensureColumnExists(connection, "visitors", "created_at",
                "ALTER TABLE visitors ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");

            // Ensure core system tables exist
            ensureTableExists(connection, "CREATE TABLE IF NOT EXISTS sessions ("
                + "session_id VARCHAR(128) PRIMARY KEY,"
                + "user_id INT NOT NULL,"
                + "username VARCHAR(50) NOT NULL,"
                + "role VARCHAR(20) NOT NULL,"
                + "expires_at DATETIME NOT NULL,"
                + "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");

            ensureTableExists(connection, "CREATE TABLE IF NOT EXISTS audit_logs ("
                + "audit_id INT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id INT NULL,"
                + "username VARCHAR(50) NOT NULL,"
                + "action_type VARCHAR(30) NOT NULL,"
                + "entity_type VARCHAR(50) NOT NULL,"
                + "entity_id VARCHAR(100) NULL,"
                + "details TEXT NOT NULL,"
                + "created_at DATETIME NOT NULL,"
                + "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL)");

            // Maintenance + data migration
            cleanupExpiredSessions(connection);
            migrateLegacyPasswords(connection);

        } catch (SQLException e) {
            throw new IOException("Failed to initialize database state", e);
        }
    }

    // Executes CREATE TABLE IF NOT EXISTS
    private static void ensureTableExists(Connection connection, String createSql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(createSql)) {
            ps.executeUpdate();
        }
    }

    // Adds column only if missing (prevents duplicate ALTER errors)
    private static void ensureColumnExists(Connection connection, String table, String column, String alterSql) throws SQLException {
        String checkSql = "SELECT 1 FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ? LIMIT 1";

        try (PreparedStatement check = connection.prepareStatement(checkSql)) {
            check.setString(1, table);
            check.setString(2, column);

            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    return; // column already exists
                }
            }
        }

        try (PreparedStatement alter = connection.prepareStatement(alterSql)) {
            alter.executeUpdate();
        }
    }

    // Remove expired sessions at startup
    private static void cleanupExpiredSessions(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "DELETE FROM sessions WHERE expires_at < ?")) {

            ps.setTimestamp(1, Timestamp.valueOf(java.time.LocalDateTime.now().withNano(0)));
            ps.executeUpdate();
        }
    }

    // Convert legacy plain-text passwords to hashed format
    private static void migrateLegacyPasswords(Connection connection) throws SQLException {
        String selectSql = "SELECT user_id, password FROM users";
        String updateSql = "UPDATE users SET password = ? WHERE user_id = ?";

        try (PreparedStatement select = connection.prepareStatement(selectSql);
             ResultSet rs = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(updateSql)) {

            while (rs.next()) {
                String storedPassword = defaultString(rs.getString("password"), "");

                if (PasswordUtils.isHashedPassword(storedPassword)) {
                    continue;
                }

                update.setString(1, PasswordUtils.hashPassword(storedPassword));
                update.setInt(2, rs.getInt("user_id"));
                update.addBatch();
            }

            update.executeBatch(); // batch update for efficiency
        }
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }
}