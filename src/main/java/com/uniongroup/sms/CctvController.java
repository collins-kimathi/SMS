package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.*;
import java.util.*;

final class CctvController {
    private CctvController() {}

    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SmsApplication.requireAuthenticated(exchange);
        String sql = "SELECT c.cctv_id, c.camera_name, c.location, c.status, c.subsidiary_id, s.subsidiary_name " +
            "FROM cctv_devices c LEFT JOIN subsidiaries s ON c.subsidiary_id = s.subsidiary_id ORDER BY c.camera_name ASC";
        List<String> rows = new ArrayList<>();
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add("{" +
                    "\"id\":" + rs.getInt("cctv_id") + "," +
                    "\"cameraName\":\"" + SmsApplication.escapeJson(rs.getString("camera_name")) + "\"," +
                    "\"location\":\"" + SmsApplication.escapeJson(rs.getString("location")) + "\"," +
                    "\"status\":\"" + SmsApplication.escapeJson(rs.getString("status")) + "\"," +
                    "\"subsidiaryId\":" + rs.getInt("subsidiary_id") + "," +
                    "\"subsidiaryName\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("subsidiary_name"), "")) + "\"" +
                    "}");
            }
        }
        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleCreate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        String cameraName = SmsApplication.trim(form.get("cameraName"));
        String location = SmsApplication.trim(form.get("location"));
        String status = SmsApplication.trim(form.get("status"));
        String subsidiaryId = SmsApplication.trim(form.get("subsidiaryId"));
        if (cameraName.isEmpty() || location.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Camera name and location are required\"}");
            return;
        }
        if (status.isEmpty()) status = "Online";
        try (Connection connection = SmsApplication.getConnection()) {
            String sql = "INSERT INTO cctv_devices (camera_name, location, status, subsidiary_id) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, cameraName);
                ps.setString(2, location);
                ps.setString(3, status);
                if (subsidiaryId.isEmpty()) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, Integer.parseInt(subsidiaryId));
                ps.executeUpdate();
            }
            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "cctv_device", cameraName, "Created CCTV device " + cameraName);
        }
        SmsApplication.sendJson(exchange, 201, "{\"message\":\"CCTV device created successfully\"}");
    }

    static void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int id = SmsApplication.parseRequiredInt(form.get("cctvId"), "CCTV device is required");
        String cameraName = SmsApplication.trim(form.get("cameraName"));
        String location = SmsApplication.trim(form.get("location"));
        String status = SmsApplication.trim(form.get("status"));
        String subsidiaryId = SmsApplication.trim(form.get("subsidiaryId"));
        if (cameraName.isEmpty() || location.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Camera name and location are required\"}");
            return;
        }
        try (Connection connection = SmsApplication.getConnection()) {
            String sql = "UPDATE cctv_devices SET camera_name = ?, location = ?, status = ?, subsidiary_id = ? WHERE cctv_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, cameraName);
                ps.setString(2, location);
                ps.setString(3, status.isEmpty() ? "Online" : status);
                if (subsidiaryId.isEmpty()) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, Integer.parseInt(subsidiaryId));
                ps.setInt(5, id);
                if (ps.executeUpdate() == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"CCTV device not found\"}");
                    return;
                }
            }
            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "cctv_device", String.valueOf(id), "Updated CCTV device " + cameraName);
        }
        SmsApplication.sendJson(exchange, 200, "{\"message\":\"CCTV device updated successfully\"}");
    }

    static void handleDelete(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int id = SmsApplication.parseRequiredInt(form.get("cctvId"), "CCTV device is required");
        try (Connection connection = SmsApplication.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM cctv_devices WHERE cctv_id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"CCTV device not found\"}");
                    return;
                }
            }
            SmsApplication.recordAuditAction(connection, session.userId, "DELETE", "cctv_device", String.valueOf(id), "Deleted CCTV device");
        }
        SmsApplication.sendJson(exchange, 200, "{\"message\":\"CCTV device deleted successfully\"}");
    }
}
