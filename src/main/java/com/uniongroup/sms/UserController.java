package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.*;
import java.util.*;

// Admin-only user management (CRUD operations)
final class UserController {

    private UserController() {}

    // Return all users
    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);

        String sql = "SELECT user_id, username, role FROM users ORDER BY user_id ASC";
        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            SmsApplication.requireAdmin(session);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add("{"
                        + "\"userId\":" + rs.getInt("user_id") + ","
                        + "\"username\":\"" + SmsApplication.escapeJson(rs.getString("username")) + "\","
                        + "\"role\":\"" + SmsApplication.escapeJson(rs.getString("role")) + "\""
                        + "}");
                }
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    // Create a new user (password stored as hash)
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
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setString(2, PasswordUtils.hashPassword(password));
                ps.setString(3, role);
                ps.executeUpdate();
            }

            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "user", username, "Created user " + username);
        }

        SmsApplication.sendJson(exchange, 201, "{\"message\":\"User created successfully\"}");
    }

    // Update user; password update is optional
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

            // Fetch current values for change detection
            String currentUsername = null;
            String currentRole = null;

            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT username, role FROM users WHERE user_id = ?")) {
                ps.setInt(1, targetUserId);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        SmsApplication.sendJson(exchange, 404, "{\"message\":\"User not found\"}");
                        return;
                    }
                    currentUsername = SmsApplication.defaultString(rs.getString("username"), "");
                    currentRole = SmsApplication.defaultString(rs.getString("role"), "");
                }
            }

            // Switch query depending on password change
            String sql = password.isEmpty()
                ? "UPDATE users SET username = ?, role = ? WHERE user_id = ?"
                : "UPDATE users SET username = ?, password = ?, role = ? WHERE user_id = ?";

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, username);

                if (password.isEmpty()) {
                    ps.setString(2, role);
                    ps.setInt(3, targetUserId);
                } else {
                    ps.setString(2, PasswordUtils.hashPassword(password));
                    ps.setString(3, role);
                    ps.setInt(4, targetUserId);
                }

                if (ps.executeUpdate() == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"User not found\"}");
                    return;
                }
            }

            // Revoke sessions if credentials or role changed
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

    // Delete user (self-deletion is blocked)
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
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, targetUserId);

                if (ps.executeUpdate() == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"User not found\"}");
                    return;
                }

                SmsApplication.recordAuditAction(connection, session.userId, "DELETE", "user", String.valueOf(targetUserId), "Deleted user account");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1451) {
                    SmsApplication.sendJson(exchange, 409, "{\"message\":\"User cannot be deleted because there are related records\"}");
                    return;
                }
                throw e;
            }
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"User deleted successfully\"}");
    }
}