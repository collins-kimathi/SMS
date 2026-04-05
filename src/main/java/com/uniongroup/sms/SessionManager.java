package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.sql.*;
import java.util.Base64;

// Manages session lifecycle and cookie-based authentication
final class SessionManager {

    private SessionManager() {}

    // Enforce authentication; throws if no valid session
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

    // Resolve session from cookie; returns null if missing/invalid/expired
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

        // Extract session ID from cookie header
        String sessionId = null;
        for (String cookie : cookieHeader.split(";")) {
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
             PreparedStatement ps = connection.prepareStatement(
                 "SELECT session_id, user_id, username, role, expires_at FROM sessions WHERE session_id = ?")) {

            ps.setString(1, sessionId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                // Expire session if past TTL
                Timestamp expiresAt = rs.getTimestamp("expires_at");
                if (expiresAt == null || expiresAt.getTime() < System.currentTimeMillis()) {
                    deleteSession(connection, sessionId);
                    return null;
                }

                return new SessionInfo(
                    rs.getString("session_id"),
                    rs.getInt("user_id"),
                    rs.getString("username"),
                    rs.getString("role"),
                    expiresAt.getTime()
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load session", e);
        }
    }

    // Create and persist a new session
    static SessionInfo createSession(Connection connection, int userId, String username, String role, long sessionTtlMillis) throws SQLException {
        String sessionId = generateSessionId();
        SessionInfo session = new SessionInfo(
            sessionId,
            userId,
            username,
            role,
            System.currentTimeMillis() + sessionTtlMillis
        );

        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO sessions (session_id, user_id, username, role, expires_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setInt(2, userId);
            ps.setString(3, username);
            ps.setString(4, role);
            ps.setTimestamp(5, new Timestamp(session.expiresAtMillis));
            ps.executeUpdate();
        }

        return session;
    }

    // Delete a single session (logout / expiration)
    static void deleteSession(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM sessions WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        }
    }

    // Revoke all sessions for a user (e.g., password/role change)
    static void deleteSessionsForUser(Connection connection, int userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }

    // Attach session cookie to response
    static void setSessionCookie(HttpExchange exchange, String sessionCookie, String sessionId, long sessionTtlMillis) {
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            sessionCookie + "=" + sessionId
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (sessionTtlMillis / 1000L)
        );
    }

    // Clear session cookie on client
    static void clearSessionCookie(HttpExchange exchange, String sessionCookie) {
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            sessionCookie + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
        );
    }

    // Generate secure random session ID
    private static String generateSessionId() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}