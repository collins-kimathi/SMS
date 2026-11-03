package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.*;
import java.util.*;

final class NotificationController {
    private NotificationController() {}

    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        String sql = "SELECT notification_id, title, message, severity, is_read, created_at FROM notifications WHERE user_id IS NULL OR user_id = ? ORDER BY created_at DESC";
        List<String> rows = new ArrayList<>();
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, session.userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add("{" +
                        "\"id\":" + rs.getInt("notification_id") + "," +
                        "\"title\":\"" + SmsApplication.escapeJson(rs.getString("title")) + "\"," +
                        "\"message\":\"" + SmsApplication.escapeJson(rs.getString("message")) + "\"," +
                        "\"severity\":\"" + SmsApplication.escapeJson(rs.getString("severity")) + "\"," +
                        "\"isRead\":" + rs.getInt("is_read") + "," +
                        "\"createdAt\":\"" + rs.getTimestamp("created_at") + "\"" +
                        "}");
                }
            }
        }
        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleMarkRead(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int id = SmsApplication.parseRequiredInt(form.get("notificationId"), "Notification is required");
        try (Connection connection = SmsApplication.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE notifications SET is_read = 1 WHERE notification_id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }
        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Notification marked as read\"}");
    }
}
