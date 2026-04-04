package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Base64;

// Shared session persistence and cookie handling for server-side authentication.
final class SessionManager {

    private SessionManager() {
    }

    static SessionInfo requireAuthenticated(
        HttpExchange exchange,
        String dbUrl,
        String dbUser,
        String dbPassword,
        String sessionCookie
    ) {
        SessionInfo session = getOptionalSession(exchange, dbUrl, dbUser, dbPassword, sessionCookie);
        if (session == null) {
            throw new UnauthorizedException("Login required");
        }
        return session;
    }

    static SessionInfo getOptionalSession(
        HttpExchange exchange,
        String dbUrl,
        String dbUser,
        String dbPassword,
        String sessionCookie
    ) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }

        String sessionId = null;
        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String[] pair = cookie.trim().split("=", 2);
            if (pair.length == 2 && sessionCookie.equals(pair[0])) {
                sessionId = pair[1];
                break;
            }
        }

        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT session_id, user_id, username, role, expires_at FROM sessions WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                Timestamp expiresAt = resultSet.getTimestamp("expires_at");
                if (expiresAt == null || expiresAt.getTime() < System.currentTimeMillis()) {
                    deleteSession(connection, sessionId);
                    return null;
                }

                return new SessionInfo(
                    resultSet.getString("session_id"),
                    resultSet.getInt("user_id"),
                    resultSet.getString("username"),
                    resultSet.getString("role"),
                    expiresAt.getTime()
                );
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load session", exception);
        }
    }

    static SessionInfo createSession(Connection connection, int userId, String username, String role, long sessionTtlMillis) throws SQLException {
        String sessionId = generateSessionId();
        SessionInfo session = new SessionInfo(sessionId, userId, username, role, System.currentTimeMillis() + sessionTtlMillis);
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO sessions (session_id, user_id, username, role, expires_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, sessionId);
            statement.setInt(2, userId);
            statement.setString(3, username);
            statement.setString(4, role);
            statement.setTimestamp(5, new Timestamp(session.expiresAtMillis));
            statement.executeUpdate();
        }
        return session;
    }

    static void deleteSession(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        }
    }

    static void deleteSessionsForUser(Connection connection, int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
            statement.setInt(1, userId);
            statement.executeUpdate();
        }
    }

    static void setSessionCookie(HttpExchange exchange, String sessionCookie, String sessionId, long sessionTtlMillis) {
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            sessionCookie + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (sessionTtlMillis / 1000L)
        );
    }

    static void clearSessionCookie(HttpExchange exchange, String sessionCookie) {
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            sessionCookie + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
        );
    }

    private static String generateSessionId() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
