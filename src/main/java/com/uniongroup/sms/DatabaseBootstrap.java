package com.uniongroup.sms;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

// Startup-only database preparation so the main HTTP server class can stay focused on requests.
final class DatabaseBootstrap {

    private DatabaseBootstrap() {
    }

    static void initialize(String dbUrl, String dbUser, String dbPassword) throws IOException {
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
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
            cleanupExpiredSessions(connection);
            migrateLegacyPasswords(connection);
        } catch (SQLException exception) {
            throw new IOException("Failed to initialize database state", exception);
        }
    }

    private static void ensureTableExists(Connection connection, String createSql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(createSql)) {
            statement.executeUpdate();
        }
    }

    private static void ensureColumnExists(Connection connection, String tableName, String columnName, String alterSql) throws SQLException {
        String checkSql = "SELECT 1 FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ? LIMIT 1";

        try (PreparedStatement checkStatement = connection.prepareStatement(checkSql)) {
            checkStatement.setString(1, tableName);
            checkStatement.setString(2, columnName);

            try (ResultSet resultSet = checkStatement.executeQuery()) {
                if (resultSet.next()) {
                    return;
                }
            }
        }

        try (PreparedStatement alterStatement = connection.prepareStatement(alterSql)) {
            alterStatement.executeUpdate();
        }
    }

    private static void cleanupExpiredSessions(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE expires_at < ?")) {
            statement.setTimestamp(1, Timestamp.valueOf(java.time.LocalDateTime.now().withNano(0)));
            statement.executeUpdate();
        }
    }

    private static void migrateLegacyPasswords(Connection connection) throws SQLException {
        String selectSql = "SELECT user_id, password FROM users";
        String updateSql = "UPDATE users SET password = ? WHERE user_id = ?";

        try (PreparedStatement selectStatement = connection.prepareStatement(selectSql);
             ResultSet resultSet = selectStatement.executeQuery();
             PreparedStatement updateStatement = connection.prepareStatement(updateSql)) {
            while (resultSet.next()) {
                String storedPassword = defaultString(resultSet.getString("password"), "");
                if (PasswordUtils.isHashedPassword(storedPassword)) {
                    continue;
                }

                updateStatement.setString(1, PasswordUtils.hashPassword(storedPassword));
                updateStatement.setInt(2, resultSet.getInt("user_id"));
                updateStatement.addBatch();
            }

            updateStatement.executeBatch();
        }
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
