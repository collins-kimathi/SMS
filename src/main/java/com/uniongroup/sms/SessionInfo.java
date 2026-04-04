package com.uniongroup.sms;

// Lightweight session model passed around after a request is authenticated.
final class SessionInfo {
    final String sessionId;
    final int userId;
    final String username;
    final String role;
    final long expiresAtMillis;

    SessionInfo(String sessionId, int userId, String username, String role, long expiresAtMillis) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.expiresAtMillis = expiresAtMillis;
    }
}
