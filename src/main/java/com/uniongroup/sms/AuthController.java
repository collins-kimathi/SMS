package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

// Handles login, logout, and session lookup endpoints.
final class AuthController {

    private AuthController() {
    }

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
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    SmsApplication.sendJson(exchange, 401, "{\"message\":\"Invalid login credentials\"}");
                    return;
                }

                String storedPassword = SmsApplication.defaultString(resultSet.getString("password"), "");
                if (!PasswordUtils.verifyPassword(password, storedPassword)) {
                    SmsApplication.sendJson(exchange, 401, "{\"message\":\"Invalid login credentials\"}");
                    return;
                }

                PasswordUtils.upgradePlainTextPasswordIfNeeded(connection, resultSet.getInt("user_id"), storedPassword);

                SessionInfo session = SessionManager.createSession(
                    connection,
                    resultSet.getInt("user_id"),
                    resultSet.getString("username"),
                    SmsApplication.defaultString(resultSet.getString("role"), "Security"),
                    SmsApplication.sessionTtlMillis()
                );
                SmsApplication.recordAuditAction(connection, session.userId, "LOGIN", "session", session.sessionId, "User signed in");
                SessionManager.setSessionCookie(
                    exchange,
                    SmsApplication.sessionCookieName(),
                    session.sessionId,
                    SmsApplication.sessionTtlMillis()
                );

                String response = "{"
                    + "\"userId\":" + resultSet.getInt("user_id") + ","
                    + "\"username\":\"" + SmsApplication.escapeJson(resultSet.getString("username")) + "\","
                    + "\"role\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(resultSet.getString("role"), "Security")) + "\""
                    + "}";
                SmsApplication.sendJson(exchange, 200, response);
            }
        }
    }

    static void handleSession(HttpExchange exchange) throws IOException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.sendJson(exchange, 200, SmsApplication.sessionToJson(session));
    }

    static void handleLogout(HttpExchange exchange) throws IOException {
        SessionInfo session = SmsApplication.getOptionalSession(exchange);
        if (session != null) {
            try (Connection connection = SmsApplication.getConnection()) {
                SessionManager.deleteSession(connection, session.sessionId);
                SmsApplication.recordAuditAction(connection, session.userId, "LOGOUT", "session", session.sessionId, "User signed out");
            } catch (SQLException exception) {
                throw new IOException("Failed to end session", exception);
            }
        }
        SessionManager.clearSessionCookie(exchange, SmsApplication.sessionCookieName());
        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Logged out successfully\"}");
    }
}
