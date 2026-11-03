package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.*;
import java.util.*;

final class SubsidiaryController {
    private SubsidiaryController() {}

    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SmsApplication.requireAuthenticated(exchange);
        String sql = "SELECT subsidiary_id, subsidiary_name, location, contact_number FROM subsidiaries ORDER BY subsidiary_name ASC";
        List<String> rows = new ArrayList<>();
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add("{"
                    + "\"id\":" + rs.getInt("subsidiary_id") + ","
                    + "\"name\":\"" + SmsApplication.escapeJson(rs.getString("subsidiary_name")) + "\","
                    + "\"location\":\"" + SmsApplication.escapeJson(rs.getString("location")) + "\","
                    + "\"contactNumber\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("contact_number"), "")) + "\""
                    + "}");
            }
        }
        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleCreate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        String name = SmsApplication.trim(form.get("name"));
        String location = SmsApplication.trim(form.get("location"));
        String contact = SmsApplication.trim(form.get("contactNumber"));
        if (name.isEmpty() || location.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Subsidiary name and location are required\"}");
            return;
        }
        try (Connection connection = SmsApplication.getConnection()) {
            String sql = "INSERT INTO subsidiaries (subsidiary_name, location, contact_number) VALUES (?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, location);
                ps.setString(3, contact);
                ps.executeUpdate();
            }
            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "subsidiary", name, "Created subsidiary " + name);
        }
        SmsApplication.sendJson(exchange, 201, "{\"message\":\"Subsidiary created successfully\"}");
    }

    static void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int id = SmsApplication.parseRequiredInt(form.get("subsidiaryId"), "Subsidiary is required");
        String name = SmsApplication.trim(form.get("name"));
        String location = SmsApplication.trim(form.get("location"));
        String contact = SmsApplication.trim(form.get("contactNumber"));
        if (name.isEmpty() || location.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Subsidiary name and location are required\"}");
            return;
        }
        try (Connection connection = SmsApplication.getConnection()) {
            String sql = "UPDATE subsidiaries SET subsidiary_name = ?, location = ?, contact_number = ? WHERE subsidiary_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, location);
                ps.setString(3, contact);
                ps.setInt(4, id);
                if (ps.executeUpdate() == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"Subsidiary not found\"}");
                    return;
                }
            }
            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "subsidiary", String.valueOf(id), "Updated subsidiary " + name);
        }
        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Subsidiary updated successfully\"}");
    }

    static void handleDelete(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int id = SmsApplication.parseRequiredInt(form.get("subsidiaryId"), "Subsidiary is required");
        try (Connection connection = SmsApplication.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM subsidiaries WHERE subsidiary_id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"Subsidiary not found\"}");
                    return;
                }
            }
            SmsApplication.recordAuditAction(connection, session.userId, "DELETE", "subsidiary", String.valueOf(id), "Deleted subsidiary record");
        } catch (SQLException e) {
            if (e.getErrorCode() == 1451) {
                SmsApplication.sendJson(exchange, 409, "{\"message\":\"Subsidiary cannot be deleted because related records exist\"}");
                return;
            }
            throw e;
        }
        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Subsidiary deleted successfully\"}");
    }
}
