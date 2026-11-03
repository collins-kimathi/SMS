package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.*;
import java.util.*;

final class AlarmController {
    private AlarmController() {}

    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SmsApplication.requireAuthenticated(exchange);
        String sql = "SELECT a.alarm_id, a.alarm_type, a.status, a.date_time, a.cctv_id, c.camera_name " +
            "FROM alarm_events a JOIN cctv_devices c ON a.cctv_id = c.cctv_id ORDER BY a.date_time DESC";
        List<String> rows = new ArrayList<>();
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add("{" +
                    "\"id\":" + rs.getInt("alarm_id") + "," +
                    "\"alarmType\":\"" + SmsApplication.escapeJson(rs.getString("alarm_type")) + "\"," +
                    "\"status\":\"" + SmsApplication.escapeJson(rs.getString("status")) + "\"," +
                    "\"dateTime\":\"" + rs.getTimestamp("date_time") + "\"," +
                    "\"cctvId\":" + rs.getInt("cctv_id") + "," +
                    "\"cameraName\":\"" + SmsApplication.escapeJson(rs.getString("camera_name")) + "\"" +
                    "}");
            }
        }
        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleCreate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireSecurityOfficer(session);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        String alarmType = SmsApplication.trim(form.get("alarmType"));
        String status = SmsApplication.trim(form.get("status"));
        String cctvId = SmsApplication.trim(form.get("cctvId"));
        if (alarmType.isEmpty() || cctvId.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Alarm type and CCTV device are required\"}");
            return;
        }
        if (status.isEmpty()) status = "New";
        try (Connection connection = SmsApplication.getConnection()) {
            String sql = "INSERT INTO alarm_events (alarm_type, status, date_time, cctv_id) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, alarmType);
                ps.setString(2, status);
                ps.setTimestamp(3, Timestamp.valueOf(java.time.LocalDateTime.now().withNano(0)));
                ps.setInt(4, Integer.parseInt(cctvId));
                ps.executeUpdate();
            }
            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "alarm_event", alarmType, "Created alarm event " + alarmType);
            if ("Critical".equalsIgnoreCase(status) || "High".equalsIgnoreCase(alarmType)) {
                createNotification(connection, "Critical alarm: " + alarmType, "Alarm reported on CCTV device " + cctvId, "Critical", null);
            }
        }
        SmsApplication.sendJson(exchange, 201, "{\"message\":\"Alarm event created successfully\"}");
    }

    static void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireSecurityOfficer(session);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int id = SmsApplication.parseRequiredInt(form.get("alarmId"), "Alarm is required");
        String status = SmsApplication.trim(form.get("status"));
        if (status.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Status is required\"}");
            return;
        }
        try (Connection connection = SmsApplication.getConnection()) {
            String sql = "UPDATE alarm_events SET status = ? WHERE alarm_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setInt(2, id);
                if (ps.executeUpdate() == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"Alarm event not found\"}");
                    return;
                }
            }
            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "alarm_event", String.valueOf(id), "Updated alarm status to " + status);
        }
        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Alarm status updated successfully\"}");
    }

    static void handleDelete(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int id = SmsApplication.parseRequiredInt(form.get("alarmId"), "Alarm is required");
        try (Connection connection = SmsApplication.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM alarm_events WHERE alarm_id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"Alarm event not found\"}");
                    return;
                }
            }
            SmsApplication.recordAuditAction(connection, session.userId, "DELETE", "alarm_event", String.valueOf(id), "Deleted alarm event");
        }
        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Alarm event deleted successfully\"}");
    }

    static void createNotification(Connection connection, String title, String message, String severity, Integer userId) throws SQLException {
        String sql = "INSERT INTO notifications (title, message, severity, is_read, created_at, user_id) VALUES (?, ?, ?, 0, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setString(2, message);
            ps.setString(3, severity);
            ps.setTimestamp(4, Timestamp.valueOf(java.time.LocalDateTime.now().withNano(0)));
            if (userId == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setInt(5, userId);
            ps.executeUpdate();
        }
    }
}
