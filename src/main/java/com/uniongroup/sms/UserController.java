package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Handles admin user-management endpoints.
final class UserController {

    private UserController() {
    }

    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        String sql = "SELECT user_id, username, role FROM users ORDER BY user_id ASC";
        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            SmsApplication.requireAdmin(session);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add("{"
                        + "\"userId\":" + resultSet.getInt("user_id") + ","
                        + "\"username\":\"" + SmsApplication.escapeJson(resultSet.getString("username")) + "\","
                        + "\"role\":\"" + SmsApplication.escapeJson(resultSet.getString("role")) + "\""
                        + "}");
                }
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleCreate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        String username = SmsApplication.trim(form.get("username"));
        String password = SmsApplication.trim(form.get("password"));
        String role = SmsApplication.trim(form.get("role"));

        if (username.isEmpty() || password.isEmpty() || role.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"All user fields are required\"}");
            return;
        }

        try (Connection connection = SmsApplication.getConnection()) {
            SmsApplication.requireAdmin(session);

            if (SmsApplication.usernameExists(connection, username, null)) {
                SmsApplication.sendJson(exchange, 409, "{\"message\":\"Username already exists\"}");
                return;
            }

            String sql = "INSERT INTO users (username, password, role) VALUES (?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                statement.setString(2, PasswordUtils.hashPassword(password));
                statement.setString(3, role);
                statement.executeUpdate();
            }
            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "user", username, "Created user " + username);
        }

        SmsApplication.sendJson(exchange, 201, "{\"message\":\"User created successfully\"}");
    }

    static void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int targetUserId = SmsApplication.parseRequiredInt(form.get("targetUserId"), "Target user is required");
        String username = SmsApplication.trim(form.get("username"));
        String password = SmsApplication.trim(form.get("password"));
        String role = SmsApplication.trim(form.get("role"));

        if (username.isEmpty() || role.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Username and role are required\"}");
            return;
        }

        try (Connection connection = SmsApplication.getConnection()) {
            SmsApplication.requireAdmin(session);

            if (SmsApplication.usernameExists(connection, username, targetUserId)) {
                SmsApplication.sendJson(exchange, 409, "{\"message\":\"Username already exists\"}");
                return;
            }

            String currentUsername = null;
            String currentRole = null;
            try (PreparedStatement currentStatement = connection.prepareStatement(
                "SELECT username, role FROM users WHERE user_id = ?")) {
                currentStatement.setInt(1, targetUserId);
                try (ResultSet resultSet = currentStatement.executeQuery()) {
                    if (!resultSet.next()) {
                        SmsApplication.sendJson(exchange, 404, "{\"message\":\"User not found\"}");
                        return;
                    }
                    currentUsername = SmsApplication.defaultString(resultSet.getString("username"), "");
                    currentRole = SmsApplication.defaultString(resultSet.getString("role"), "");
                }
            }

            String sql = password.isEmpty()
                ? "UPDATE users SET username = ?, role = ? WHERE user_id = ?"
                : "UPDATE users SET username = ?, password = ?, role = ? WHERE user_id = ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                if (password.isEmpty()) {
                    statement.setString(2, role);
                    statement.setInt(3, targetUserId);
                } else {
                    statement.setString(2, PasswordUtils.hashPassword(password));
                    statement.setString(3, role);
                    statement.setInt(4, targetUserId);
                }

                int updated = statement.executeUpdate();
                if (updated == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"User not found\"}");
                    return;
                }
            }

            boolean revokeSessions = !password.isEmpty()
                || !currentUsername.equals(username)
                || !currentRole.equals(role);
            if (revokeSessions) {
                SessionManager.deleteSessionsForUser(connection, targetUserId);
            }
            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "user", String.valueOf(targetUserId), "Updated user " + username);

            SmsApplication.sendJson(exchange, 200, "{"
                + "\"message\":\"User updated successfully\","
                + "\"sessionRevoked\":" + (revokeSessions ? "true" : "false") + ","
                + "\"updatedCurrentUser\":" + (targetUserId == session.userId ? "true" : "false")
                + "}");
        }
    }

    static void handleDelete(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int targetUserId = SmsApplication.parseRequiredInt(form.get("targetUserId"), "Target user is required");

        if (session.userId == targetUserId) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"You cannot delete the currently signed-in admin\"}");
            return;
        }

        try (Connection connection = SmsApplication.getConnection()) {
            SmsApplication.requireAdmin(session);

            String sql = "DELETE FROM users WHERE user_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, targetUserId);
                int deleted = statement.executeUpdate();
                if (deleted == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"User not found\"}");
                    return;
                }
                SmsApplication.recordAuditAction(connection, session.userId, "DELETE", "user", String.valueOf(targetUserId), "Deleted user account");
            } catch (SQLException exception) {
                if (exception.getErrorCode() == 1451) {
                    SmsApplication.sendJson(exchange, 409, "{\"message\":\"User cannot be deleted because there are related records\"}");
                    return;
                }
                throw exception;
            }
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"User deleted successfully\"}");
    }
}
