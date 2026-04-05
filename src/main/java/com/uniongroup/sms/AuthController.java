package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.*;
import java.util.Map;

// Authentication endpoints (login, session check, logout)
final class AuthController {

    private AuthController() {}

    // Handle user login and session creation
    static void handleLogin(HttpExchange exchange) throws IOException, SQLException {

        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        String username = SmsApplication.trim(form.get("username"));
        String password = SmsApplication.trim(form.get("password"));

        if (username.isEmpty() || password.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"All fields are required\"}");
            return;
        }

        String sql = "SELECT user_id, username, password, role FROM users WHERE username = ?";

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setString(1, username);

            try (ResultSet rs = ps.executeQuery()) {

                // Avoid revealing whether username exists
                if (!rs.next()) {
                    SmsApplication.sendJson(exchange, 401, "{\"message\":\"Invalid login credentials\"}");
                    return;
                }

                String storedPassword = SmsApplication.defaultString(rs.getString("password"), "");

                // Verify password (supports legacy + hashed)
                if (!PasswordUtils.verifyPassword(password, storedPassword)) {
                    SmsApplication.sendJson(exchange, 401, "{\"message\":\"Invalid login credentials\"}");
                    return;
                }

                // Upgrade plain-text passwords transparently
                PasswordUtils.upgradePlainTextPasswordIfNeeded(
                    connection,
                    rs.getInt("user_id"),
                    storedPassword
                );

                // Create server-side session
                SessionInfo session = SessionManager.createSession(
                    connection,
                    rs.getInt("user_id"),
                    rs.getString("username"),
                    SmsApplication.defaultString(rs.getString("role"), "Security"),
                    SmsApplication.sessionTtlMillis()
                );

                // Audit login event
                SmsApplication.recordAuditAction(
                    connection,
                    session.userId,
                    "LOGIN",
                    "session",
                    session.sessionId,
                    "User signed in"
                );

                // Set session cookie (HttpOnly)
                SessionManager.setSessionCookie(
                    exchange,
                    SmsApplication.sessionCookieName(),
                    session.sessionId,
                    SmsApplication.sessionTtlMillis()
                );

                // Return minimal user info
                String response = "{"
                    + "\"userId\":" + rs.getInt("user_id") + ","
                    + "\"username\":\"" + SmsApplication.escapeJson(rs.getString("username")) + "\","
                    + "\"role\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("role"), "Security")) + "\""
                    + "}";

                SmsApplication.sendJson(exchange, 200, response);
            }
        }
    }

    // Return current session info (used for auto-login / session check)
    static void handleSession(HttpExchange exchange) throws IOException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.sendJson(exchange, 200, SmsApplication.sessionToJson(session));
    }

    // Logout user by deleting session + clearing cookie
    static void handleLogout(HttpExchange exchange) throws IOException {

        SessionInfo session = SmsApplication.getOptionalSession(exchange);

        if (session != null) {
            try (Connection connection = SmsApplication.getConnection()) {

                SessionManager.deleteSession(connection, session.sessionId);

                // Audit logout event
                SmsApplication.recordAuditAction(
                    connection,
                    session.userId,
                    "LOGOUT",
                    "session",
                    session.sessionId,
                    "User signed out"
                );

            } catch (SQLException e) {
                throw new IOException("Failed to end session", e);
            }
        }

        // Always clear cookie (even if session missing)
        SessionManager.clearSessionCookie(exchange, SmsApplication.sessionCookieName());

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Logged out successfully\"}");
    }
}